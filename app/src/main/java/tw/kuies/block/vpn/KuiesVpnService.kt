package tw.kuies.block.vpn

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import tw.kuies.block.R
import tw.kuies.block.core.DnsPath
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.core.ProtectionStatusHolder
import tw.kuies.block.data.DnsStatsRepository
import tw.kuies.block.dns.DnsConfig
import tw.kuies.block.dns.DnsConfigStore
import tw.kuies.block.dns.DnsProtocol
import tw.kuies.block.dns.PrivateDnsSettings
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsMessageWriter
import tw.kuies.block.dns.packet.DnsReplyPolicy
import tw.kuies.block.dns.packet.DnsTunnelOutcome
import tw.kuies.block.dns.packet.DnsTunnelPacketParser
import tw.kuies.block.dns.packet.PacketDropReason
import tw.kuies.block.dns.packet.TunnelDnsQuery
import tw.kuies.block.dns.packet.UdpDnsReplyWriter
import tw.kuies.block.doh.DohEndpointOutcome
import tw.kuies.block.doh.DohEndpointPolicy
import tw.kuies.block.doh.DohFailure
import tw.kuies.block.doh.DohResult
import tw.kuies.block.doh.DohTransport
import tw.kuies.block.doh.HostnameResolver
import tw.kuies.block.doh.OkHttpDohTransport
import tw.kuies.block.doh.PlainDnsBootstrap
import tw.kuies.block.doh.VpnSocketProtector
import tw.kuies.block.doh.toProtectionError
import tw.kuies.block.filter.FilterCoordinatorHolder
import tw.kuies.block.filter.FilterSnapshotHolder
import tw.kuies.block.filter.LocalFilterStats

/**
 * The DNS-only local VPN.
 *
 * How it protects the user's normal connectivity:
 *  * Only the virtual DNS address is added as a /32 route, so only DNS traffic enters the tunnel;
 *    HTTPS, video and everything else keeps using the underlying network untouched.
 *  * No default route is ever added, and IPv6 is never configured.
 *  * Upstream sockets are protected, so the DoH connection cannot re-enter the tunnel.
 *  * Protection is only reported as active after a real DoH round trip succeeds.
 *  * When the DNS path keeps failing the service fails open: it tears the tunnel down so the
 *    system resolver takes over again, and says so plainly.
 *
 * Scope: IPv4 UDP DNS only. DNS-over-TCP and IPv6 DNS are not intercepted in this version, which
 * is documented in the UI and the release notes.
 */
class KuiesVpnService : VpnService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifications by lazy { VpnNotifications(this) }
    private val random = Random(System.nanoTime())
    private val writeLock = Any()
    private val consecutiveFailures = AtomicInteger(0)
    private val loggedDrops = ConcurrentHashMap.newKeySet<PacketDropReason>()
    private val loggedDropDetails = ConcurrentHashMap.newKeySet<String>()
    private val verificationInFlight = AtomicBoolean(false)

    /** Which DNS path this run actually installed; reported to the UI so it never overclaims. */
    private var activeDnsPath: DnsPath = DnsPath.INTERCEPTED

    private var startupBudget: StartupBudget? = null
    private var startupTimeline: StartupTimeline? = null

    /** Debug builds only; a release build never writes these timings. */
    private val debugLoggingEnabled: Boolean by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** Fires once if the startup sequence has not reached a decision within the budget. */
    private val startupWatchdog = Runnable {
        if (running && ProtectionStatusHolder.state == ProtectionState.STARTING) {
            Log.w(TAG, "Startup budget exhausted without a verified DNS round trip.")
            requestFailOpen(ProtectionError.DNS_TIMEOUT)
        }
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var tunnelInput: FileInputStream? = null

    // Written on the main thread, read from the reader and worker threads.
    @Volatile
    private var tunnelOutput: FileOutputStream? = null

    @Volatile
    private var transport: DohTransport? = null

    @Volatile
    private var routePlan: VpnRoutePlan? = null

    @Volatile
    private var running = false

    private var readerThread: Thread? = null
    private var workers: ThreadPoolExecutor? = null
    private var authorizationRevoked = false
    private var probeAttempts = 0
    private var probeHost: String = DnsConfig.DEFAULT_HOST
    private var queueFullLogged = false

    private var stopState: ProtectionState? = null
    private var stopError: ProtectionError? = null

    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Every non-VPN network the callback has reported, and the network the tunnel is currently
     * declared to ride on. Main thread only: everything that touches them already runs on
     * [mainHandler], and teardown clears both so a stopped tunnel keeps nothing.
     */
    private val detectedUnderlying = HashMap<Long, UnderlyingNetworkInfo>()
    private var appliedUnderlying: UnderlyingNetworkInfo? = null

    /**
     * The network this start was planned with. It is declared to the system in [establishAndServe],
     * because `setUnderlyingNetworks` only succeeds once the tunnel exists.
     */
    private var startupUnderlying: UnderlyingNetworkInfo? = null

    /**
     * The declaration actually installed, or null when no tunnel is running. The Private DNS change
     * handler compares against it, so an identical, coalesced or stale notification cannot rebuild.
     */
    private var appliedDeclaration: TunnelDeclaration? = null

    /**
     * Bumped for every start attempt. Async results (DNS verification, the E1 bootstrap) carry the
     * generation they were started under, so a result that arrives after the tunnel was replaced is
     * discarded instead of corrupting the new one.
     */
    private var tunnelGeneration = 0

    private var privateDnsObserver: ContentObserver? = null

    /**
     * Debounced reaction to Android's Private DNS setting changing while protection runs. Both keys
     * can change together and some ROMs notify more than once, so repeats are collapsed into one
     * evaluation; the evaluation itself is a pure comparison, so it still cannot rebuild by itself.
     */
    private val privateDnsChangeRunner = Runnable { handlePrivateDnsChange() }

    private val privateDnsObserverCallback = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (!running) return
            mainHandler.removeCallbacks(privateDnsChangeRunner)
            mainHandler.postDelayed(privateDnsChangeRunner, PRIVATE_DNS_CHANGE_DEBOUNCE_MILLIS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stop must always win, even mid-start: it clears the pending retries and tears down.
            Log.i(TAG, "Explicit stop requested via ACTION_STOP.")
            stopProtectionByUser()
            return START_NOT_STICKY
        }
        if (!VpnAuthorization.isGranted(this)) {
            Log.w(TAG, "Start requested without VPN authorization; not starting protection.")
            authorizationRevoked = true
            stopState = ProtectionStateRules.afterAuthorizationRevoked()
            stopError = null
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            startProtection()
        }
        // Never sticky: a killed or stopped service must not be restarted with a null intent.
        return START_NOT_STICKY
    }

    // --- start -------------------------------------------------------------------------------

    private fun startProtection() {
        running = true
        isRunning = true
        // One generation per start attempt: async results tagged with an older one are discarded.
        tunnelGeneration++
        val timeline = StartupTimeline { SystemClock.elapsedRealtime() }
        startupTimeline = timeline
        startupBudget = StartupBudget(STARTUP_TOTAL_TIMEOUT_MILLIS) { SystemClock.elapsedRealtime() }
        // A hard ceiling: the UI can never sit on 「準備中」 longer than this, whatever happens.
        mainHandler.postDelayed(startupWatchdog, STARTUP_TOTAL_TIMEOUT_MILLIS)
        DnsStatsRepository.attach(this)
        notifications.ensureChannel()
        notifications.cancelStopped()
        startForegroundCompat(
            notifications.buildOngoing(
                getString(R.string.notification_starting_title),
                getString(R.string.notification_starting_text)
            )
        )
        ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStarting())

        connectivity = getSystemService(ConnectivityManager::class.java)
        val findStartedAt = SystemClock.elapsedRealtime()
        val underlying = UnderlyingNetworkFinder.find(connectivity)
        logStartupDebug("underlyingNetworkFindMs=${SystemClock.elapsedRealtime() - findStartedAt}")
        if (underlying == null) {
            Log.w(TAG, "No usable non-VPN network for the DoH bootstrap.")
            requestFailOpen(ProtectionError.NO_UNDERLYING_NETWORK)
            return
        }
        // Recorded, not yet declared: VpnService.setUnderlyingNetworks only succeeds once the VPN is
        // established, so the tunnel declares this in establishAndServe(), right after establish().
        startupUnderlying = underlying
        detectedUnderlying[underlying.candidate.handle] = underlying
        timeline.mark("underlyingReady")

        // Validate the transport before creating a tunnel we could not serve.
        val created = createTransport(underlying.dnsServers)
        if (created == null) {
            requestFailOpen(ProtectionError.ENDPOINT_NOT_DOH)
            return
        }
        transport = created
        timeline.mark("transportReady")

        val plan = VpnRoutePlan.DEFAULT
        val problems = VpnRoutePlanPolicy.problemsWith(plan)
        if (problems.isNotEmpty()) {
            Log.e(TAG, "Refusing an unsafe route plan: ${problems.joinToString()}")
            requestFailOpen(ProtectionError.TUN_ESTABLISH_FAILED)
            return
        }
        routePlan = plan

        // Publish the local filter's rules before the tunnel can serve a query. The snapshot comes
        // from stored settings and stored list text only, so it needs no network — and it is posted
        // to the coordinator's own thread, so this (main) thread never waits for a parse.
        FilterCoordinatorHolder.get(this).publishCurrentAsync()

        // One place only decides the tunnel configuration; nothing else branches on the mode.
        val privateDns = PrivateDnsSettings.read(this)
        val strictPrivateDns = privateDns.isStrictHostname
        val experimentMode = DnsExperiment.mode(this, strictPrivateDns)
        logStartupDebug(
            "dnsDiag stage=start privateDnsMode=${privateDns.mode.name} strict=$strictPrivateDns " +
                "configured=${experimentMode.name} override=${DnsExperiment.armedMode()?.name ?: "none"} " +
                "generation=$tunnelGeneration"
        )
        if (experimentMode == DnsExperimentMode.E1_REAL_DOT_ADDRESS) {
            // E1 needs the real DoT address, and the bootstrap blocks: resolve off the main thread.
            resolveRealDotAddress(underlying.dnsServers, plan, timeline)
            return
        }
        val declaration = DnsExperimentPlanner.declarationFor(experimentMode, plan, realDotAddress = null)
        if (declaration == null) {
            Log.e(TAG, "No safe tunnel declaration for $experimentMode.")
            requestFailOpen(ProtectionError.TUN_ESTABLISH_FAILED)
            return
        }
        establishAndServe(declaration, plan, timeline)
    }

    /**
     * Resolves the real DoT server address for the E1 experiment on a background thread, then
     * continues the start on the main thread. It never falls back to a hard-coded or unverified
     * address: without a resolved address the experiment is abandoned and protection fails open,
     * so a test tunnel that could break the network is never created.
     */
    private fun resolveRealDotAddress(
        underlyingDnsServers: List<InetSocketAddress>,
        plan: VpnRoutePlan,
        timeline: StartupTimeline
    ) {
        val host = DnsConfigStore(this).load().host
        val bootstrap = PlainDnsBootstrap(socketProtector(), underlyingDnsServers)
        val generation = tunnelGeneration
        val thread = Thread({
            val startedAt = SystemClock.elapsedRealtime()
            val addresses = runCatching { bootstrap.resolve(host) }.getOrDefault(emptyList())
            val ipv4 = addresses.firstOrNull { it is Inet4Address }?.hostAddress
            logStartupDebug(
                "e1DotResolveMs=${SystemClock.elapsedRealtime() - startedAt} resolvedCount=${addresses.size}"
            )
            if (LOG_DROP_ENDPOINTS) {
                // Temporary address form, debug only, gated on the same switch as the drop detail.
                Log.d(TAG, "e1DotAddress=${ipv4 ?: "none"}")
            }
            mainHandler.post {
                // A resolution started for an earlier start attempt must not build a tunnel now.
                if (generation != tunnelGeneration) return@post
                if (!running) return@post
                val declaration = DnsExperimentPlanner.declarationFor(
                    DnsExperimentMode.E1_REAL_DOT_ADDRESS,
                    plan,
                    ipv4
                )
                if (declaration == null) {
                    Log.w(TAG, "E1 has no usable real DoT address; not establishing a tunnel.")
                    requestFailOpen(ProtectionError.DNS_UNREACHABLE)
                    return@post
                }
                establishAndServe(declaration, plan, timeline)
            }
        }, "kuies-e1-dot-resolve")
        thread.isDaemon = true
        thread.start()
    }

    private fun establishAndServe(
        declaration: TunnelDeclaration,
        plan: VpnRoutePlan,
        timeline: StartupTimeline
    ) {
        val tunnelStartedAt = SystemClock.elapsedRealtime()
        val descriptor = establishTunnel(declaration)
        logStartupDebug("tunnelEstablishMs=${SystemClock.elapsedRealtime() - tunnelStartedAt}")
        if (descriptor == null) {
            requestFailOpen(ProtectionError.TUN_ESTABLISH_FAILED)
            return
        }
        tunnel = descriptor
        tunnelInput = FileInputStream(descriptor.fileDescriptor)
        tunnelOutput = FileOutputStream(descriptor.fileDescriptor)
        // Only declaring our own virtual DNS means queries will actually reach the tunnel; anything
        // else is the strict Private DNS case where the system keeps the resolution path.
        activeDnsPath = if (declaration.dnsServer == plan.dnsServerAddress) {
            DnsPath.INTERCEPTED
        } else {
            DnsPath.SYSTEM_PRIVATE_DNS
        }
        appliedDeclaration = declaration
        logStartupDebug(
            "dnsDiag stage=established declaration=${declaration.kind(plan.dnsServerAddress)} " +
                "generation=$tunnelGeneration"
        )
        workers = newWorkerPool()
        // The tunnel exists now, so the upstream network can be declared for real, and from here on
        // it is kept aligned with the network the system is actually using.
        declareUnderlyingNetwork(startupUnderlying)
        registerUnderlyingNetworkCallback()
        registerPrivateDnsObserver()
        startReaderThread(plan)
        timeline.mark("tunnelReady")
        verifyDnsRoundTrip()
    }

    private fun establishTunnel(declaration: TunnelDeclaration): ParcelFileDescriptor? = try {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(declaration.mtu)
            .addAddress(declaration.interfaceAddress, declaration.interfacePrefixLength)
        // A null DNS server or route is how the E1/E2 experiments leave them undeclared; BASELINE
        // always supplies both, so the shipped configuration is byte-for-byte unchanged.
        declaration.dnsServer?.let { builder.addDnsServer(it) }
        declaration.dnsRoute?.let { builder.addRoute(it.address, it.prefixLength) }
        builder.setBlocking(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Restrict the tunnel to IPv4 so IPv6 can never be captured without a path to forward it.
            builder.allowFamily(OsConstants.AF_INET)
        }
        builder.establish()
    } catch (exception: Exception) {
        Log.e(TAG, "establish() failed: ${exception.javaClass.simpleName}")
        null
    }

    private fun createTransport(dnsServers: List<InetSocketAddress>): DohTransport? {
        val config = DnsConfigStore(this).load()
        if (config.protocol != DnsProtocol.DOH) {
            Log.e(TAG, "Configured transport is ${config.protocol}; only DoH is implemented.")
            return null
        }
        val endpoint = when (val outcome = DohEndpointPolicy.build(config)) {
            is DohEndpointOutcome.Invalid -> {
                Log.e(TAG, "Invalid DoH endpoint: ${outcome.failure}")
                return null
            }
            is DohEndpointOutcome.Ok -> outcome.endpoint
        }
        probeHost = config.host
        val protector = socketProtector()
        val bootstrap = PlainDnsBootstrap(protector, dnsServers)
        // Debug-only: separate the plain-DNS bootstrap time from the TLS/HTTP time. Logs a duration
        // and how many addresses came back — a count only, never an address.
        val timedResolver = HostnameResolver { host ->
            val startedAt = SystemClock.elapsedRealtime()
            val resolved = bootstrap.resolve(host)
            logStartupDebug(
                "dohBootstrapMs=${SystemClock.elapsedRealtime() - startedAt} resolvedCount=${resolved.size}"
            )
            resolved
        }
        return OkHttpDohTransport(
            endpoint = endpoint,
            protector = protector,
            resolver = timedResolver
        )
    }

    private fun socketProtector(): VpnSocketProtector = object : VpnSocketProtector {
        override fun protect(socket: Socket): Boolean = this@KuiesVpnService.protect(socket)

        override fun protect(socket: DatagramSocket): Boolean = this@KuiesVpnService.protect(socket)
    }

    private fun newWorkerPool(): ThreadPoolExecutor = ThreadPoolExecutor(
        WORKER_THREADS,
        WORKER_THREADS,
        IDLE_THREAD_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "kuies-dns-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    // --- underlying network changes -----------------------------------------------------------

    /**
     * Declares [info] as the network the tunnel uses upstream. The result is checked: the system
     * refuses the call unless the VPN is established, and a refusal has to stay visible — an
     * unaccepted declaration is not recorded as applied, so the next event retries it.
     */
    private fun declareUnderlyingNetwork(info: UnderlyingNetworkInfo?) {
        val network = info?.network ?: return
        if (setUnderlyingNetworks(arrayOf(network))) {
            appliedUnderlying = info
        } else {
            appliedUnderlying = null
            Log.w(TAG, "The system did not accept the underlying network for the tunnel.")
        }
    }

    /**
     * Watches every non-VPN network with internet access. Which of them the tunnel uses is decided
     * afterwards by [UnderlyingNetworkPolicy], not by the order the events arrive in.
     */
    private fun registerUnderlyingNetworkCallback() {
        val manager = connectivity ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val generation = tunnelGeneration
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onUnderlyingNetworkEvent(generation, network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Validation and metering are part of the ranking, so this event can move the tunnel.
                onUnderlyingNetworkEvent(generation, network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                onUnderlyingNetworkEvent(generation, network)
            }

            override fun onLost(network: Network) {
                onUnderlyingNetworkLost(generation, network)
            }
        }
        try {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (exception: Exception) {
            Log.w(TAG, "Could not watch the underlying network: ${exception.javaClass.simpleName}")
        }
    }

    /**
     * One non-VPN network was reported or changed. The event only refreshes what we know about that
     * network; the network the tunnel rides on is chosen from the whole set afterwards, so a
     * cellular link-property update can no longer displace the Wi-Fi network the system is using.
     */
    private fun onUnderlyingNetworkEvent(generation: Int, network: Network) {
        val manager = connectivity ?: return
        val detected = UnderlyingNetworkFinder.detect(manager, network) ?: return
        mainHandler.post {
            if (!UnderlyingNetworkPolicy.isCurrentEvent(running, generation, tunnelGeneration)) return@post
            detectedUnderlying[detected.candidate.handle] = detected
            alignUnderlyingNetwork("event")
        }
    }

    private fun onUnderlyingNetworkLost(generation: Int, network: Network) {
        val handle = network.networkHandle
        mainHandler.post {
            if (!UnderlyingNetworkPolicy.isCurrentEvent(running, generation, tunnelGeneration)) return@post
            if (detectedUnderlying.remove(handle) == null) return@post
            alignUnderlyingNetwork("lost")
        }
    }

    /**
     * Applies the policy's answer, and only when it actually changed: the same network reached
     * through the same DNS servers leaves both the declaration and the DoH transport alone, so a
     * burst of callbacks cannot rebuild anything, while a real switch re-declares the network and
     * rebuilds the transport once.
     */
    private fun alignUnderlyingNetwork(trigger: String) {
        val manager = connectivity ?: return
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = detectedUnderlying.values.map { it.candidate },
            current = appliedUnderlying?.candidate,
            systemDefault = UnderlyingNetworkFinder.activeDetected(manager)?.candidate
        )
        val chosen = decision.candidate
        if (chosen == null) {
            Log.w(TAG, "No non-VPN network is available for the tunnel ($trigger).")
            return
        }
        val detected = detectedUnderlying[chosen.handle] ?: return
        val applied = appliedUnderlying
        if (!UnderlyingNetworkPolicy.upstreamChangeRequired(
                appliedNetworkHandle = applied?.network?.networkHandle,
                appliedDnsServers = applied?.dnsServers.orEmpty(),
                chosenNetworkHandle = detected.network.networkHandle,
                chosenDnsServers = detected.dnsServers
            )
        ) {
            logStartupDebug(
                "underlyingDiag action=keep reason=${decision.reason} chosen=${UnderlyingNetworkPolicy.describe(chosen)} trigger=$trigger"
            )
            return
        }
        declareUnderlyingNetwork(detected)
        Log.i(
            TAG,
            "Underlying network changed; re-bootstrapping DoH " +
                "(${UnderlyingNetworkPolicy.describe(chosen)}, reason=${decision.reason}, on=$trigger)."
        )
        if (detected.dnsServers.isEmpty()) {
            Log.w(TAG, "The underlying network declares no DNS servers; keeping the current DoH bootstrap.")
            return
        }
        // The new network invalidates the old connections, so the transport is rebuilt.
        rebuildTransport(detected.dnsServers)
    }

    private fun rebuildTransport(dnsServers: List<InetSocketAddress>) {
        val oldTransport = transport
        val newTransport = createTransport(dnsServers)
        if (oldTransport != null) {
            Thread({ runCatching { oldTransport.close() } }, "kuies-transport-rebuild").start()
        }
        transport = newTransport
        if (newTransport == null) {
            requestFailOpen(ProtectionError.ENDPOINT_NOT_DOH)
            return
        }
        if (ProtectionStatusHolder.state == ProtectionState.STARTING) {
            // A network change may earn a fresh retry, but never a fresh deadline.
            if (startupBudget?.isExhausted() == true) {
                Log.w(TAG, "Underlying network changed after the startup budget expired.")
                requestFailOpen(ProtectionError.DNS_TIMEOUT)
                return
            }
            probeAttempts = 0
            verifyDnsRoundTrip()
        }
    }

    // --- packet path -------------------------------------------------------------------------

    private fun startReaderThread(plan: VpnRoutePlan) {
        val stream = tunnelInput ?: return
        val thread = Thread({
            val buffer = ByteArray(plan.mtu)
            while (running) {
                val length = try {
                    stream.read(buffer)
                } catch (io: IOException) {
                    break
                }
                if (length <= 0) {
                    if (length < 0) break else continue
                }
                when (val outcome = DnsTunnelPacketParser.parse(buffer, length, plan.interceptTarget)) {
                    is DnsTunnelOutcome.Drop -> {
                        logDropOnce(outcome.reason)
                        logDropDetail(outcome.reason, buffer, length)
                    }
                    is DnsTunnelOutcome.Query -> dispatch(outcome.query)
                }
            }
        }, "kuies-dns-reader")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    /** Logs each drop reason once, so diagnostics stay useful without spamming or leaking content. */
    private fun logDropOnce(reason: PacketDropReason) {
        if (loggedDrops.add(reason)) {
            Log.i(TAG, "Discarding tunnel packets: $reason")
        }
    }

    /**
     * Debug-only drop detail. Each distinct combination is logged once, so Logcat stays small.
     * Reads the already-captured buffer; it changes no parsing behaviour.
     *
     * TEMPORARY (Private DNS investigation): while [LOG_DROP_ENDPOINTS] is true this also records
     * the source/destination addresses and TCP flags, which is the only way to tell whether port 853
     * traffic targets our virtual DNS or a public address. Set the flag to false (or delete this
     * branch) once the routing question is settled. Debug builds only — a release build never logs
     * an address.
     */
    private fun logDropDetail(reason: PacketDropReason, bytes: ByteArray, length: Int) {
        if (!debugLoggingEnabled) return
        if (loggedDropDetails.size >= MAX_LOGGED_DROP_DETAILS) return
        val detail = if (LOG_DROP_ENDPOINTS) {
            DropDiagnostics.describeWithEndpoints(reason, bytes, length)
        } else {
            DropDiagnostics.describe(reason, bytes, length)
        } ?: return
        if (loggedDropDetails.add(detail)) {
            Log.d(TAG, detail)
        }
    }

    /** Debug builds only. Durations and counts, never a name, an address, or query content. */
    private fun logStartupDebug(message: String) {
        if (debugLoggingEnabled) Log.d(TAG, message)
    }

    private fun logStartupSummary() {
        val timeline = startupTimeline ?: return
        logStartupDebug("startupTimeline totalMs=${timeline.elapsedMillis()} stages=[${timeline.summary()}]")
    }

    private fun dispatch(query: TunnelDnsQuery) {
        DnsStatsRepository.recordQuery()
        val executor = workers ?: return
        try {
            executor.execute { handleQuery(query) }
        } catch (rejected: RejectedExecutionException) {
            // The queue is full: never block the reader, and never grow without bound.
            if (!queueFullLogged) {
                queueFullLogged = true
                Log.w(TAG, "Query queue is full; dropping queries until it drains.")
            }
            DnsStatsRepository.recordFailure()
        }
    }

    private fun handleQuery(query: TunnelDnsQuery) {
        // Local rules answer first, without contacting anyone. This runs on a worker thread, so a
        // rule lookup never holds up the reader, and it is only ever reached by a query that actually
        // came through the tunnel — the one situation in which local filtering can be in effect.
        val localReply = localBlockReply(query)
        if (localReply != null) {
            LocalFilterStats.recordBlock()
            writeBack(query, localReply)
            return
        }
        val currentTransport = transport
        if (currentTransport == null) {
            writeBack(query, servfail(query))
            onTransportFailure(DohFailure.CONNECT_FAILED)
            return
        }
        when (val result = currentTransport.exchange(query.queryBytes)) {
            is DohResult.Failure -> {
                Log.w(TAG, "DoH exchange failed: ${result.failure}")
                writeBack(query, servfail(query))
                onTransportFailure(result.failure)
            }
            is DohResult.Success -> {
                consecutiveFailures.set(0)
                val mtu = routePlan?.mtu ?: DEFAULT_MTU
                val budget = DnsReplyPolicy.udpPayloadBudget(
                    query.ednsUdpPayloadSize,
                    DnsReplyPolicy.ipv4UdpBudget(mtu)
                )
                val payload = if (DnsReplyPolicy.fitsInUdp(result.responseBytes.size, budget)) {
                    result.responseBytes
                } else {
                    // DNS-over-TCP is not proxied, so a truncated reply is the honest answer: it
                    // tells the client the answer was too large instead of inventing one.
                    DnsMessageWriter.truncated(query.queryBytes, query.queryLength)
                }
                writeBack(query, payload)
            }
        }
    }

    private fun servfail(query: TunnelDnsQuery): ByteArray =
        DnsMessageWriter.servfail(query.queryBytes, query.queryLength)

    /**
     * The reply the local rules produce for [query], or null to let it continue upstream.
     *
     * The snapshot is read per query — a volatile read of an immutable value — so an update that
     * lands mid-flight simply applies to the next query with no restart and no lock on this path.
     */
    private fun localBlockReply(query: TunnelDnsQuery): ByteArray? {
        val snapshot = FilterSnapshotHolder.snapshot()
        if (snapshot.isEmpty) return null
        val mtu = routePlan?.mtu ?: DEFAULT_MTU
        val budget = DnsReplyPolicy.udpPayloadBudget(
            query.ednsUdpPayloadSize,
            DnsReplyPolicy.ipv4UdpBudget(mtu)
        )
        return LocalDnsFilterPolicy.blockReply(query, snapshot, budget)
    }

    private fun writeBack(query: TunnelDnsQuery, dnsPayload: ByteArray) {
        val output = tunnelOutput ?: return
        val plan = routePlan ?: return
        val packet = UdpDnsReplyWriter.buildReply(
            query = query,
            sourceAddress = plan.dnsServerAddressInt,
            sourcePort = plan.dnsPort,
            dnsPayload = dnsPayload,
            identification = random.nextInt(0x10000)
        )
        try {
            synchronized(writeLock) { output.write(packet) }
        } catch (io: IOException) {
            // Expected while the tunnel is closing.
            Log.d(TAG, "Could not write a reply; the tunnel is closing.")
        }
    }

    private fun onTransportFailure(failure: DohFailure) {
        DnsStatsRepository.recordFailure()
        val count = consecutiveFailures.incrementAndGet()
        if (count >= FAILURE_THRESHOLD) {
            Log.w(TAG, "DoH failed $count times in a row; restoring the normal network.")
            requestFailOpen(failure.toProtectionError())
        }
    }

    // --- verification and fail-open -----------------------------------------------------------

    private fun verifyDnsRoundTrip() {
        val executor = workers
        if (executor == null) {
            requestFailOpen(ProtectionError.TUN_ESTABLISH_FAILED)
            return
        }
        if (startupBudget?.isExhausted() == true) {
            Log.w(TAG, "Startup budget already exhausted before a verification attempt.")
            requestFailOpen(ProtectionError.DNS_TIMEOUT)
            return
        }
        // A network change can re-trigger verification; never let two probes overlap.
        if (!verificationInFlight.compareAndSet(false, true)) {
            logStartupDebug("dnsVerify skipped: a verification is already in flight")
            return
        }
        val attemptNumber = probeAttempts + 1
        val attemptStartedAt = SystemClock.elapsedRealtime()
        val generation = tunnelGeneration
        try {
            executor.execute {
                val result = transport?.exchange(probeQuery())
                val attemptMillis = SystemClock.elapsedRealtime() - attemptStartedAt
                mainHandler.post {
                    verificationInFlight.set(false)
                    // A result from a tunnel that has already been replaced must not touch this one.
                    if (generation != tunnelGeneration) return@post
                    if (!running) return@post
                    logStartupDebug(
                        "dnsVerify attempt=$attemptNumber durationMs=$attemptMillis result=${describeOutcome(result)}"
                    )
                    when (result) {
                        is DohResult.Success -> {
                            consecutiveFailures.set(0)
                            startupTimeline?.mark("dnsVerified")
                            mainHandler.removeCallbacks(startupWatchdog)
                            ProtectionStatusHolder.update(
                                ProtectionStateRules.afterDnsVerified(),
                                null,
                                activeDnsPath
                            )
                            notifications.updateOngoing(
                                getString(R.string.notification_active_title),
                                getString(R.string.notification_active_text)
                            )
                            Log.i(
                                TAG,
                                "DNS round trip verified; protection is active (dnsPath=${activeDnsPath.name})."
                            )
                            logStartupSummary()
                        }
                        is DohResult.Failure, null -> {
                            probeAttempts++
                            val budgetExhausted = startupBudget?.isExhausted() == true
                            val giveUp = StartupVerificationPolicy.mustStopRetrying(
                                probeAttempts,
                                PROBE_ATTEMPTS,
                                budgetExhausted
                            )
                            if (giveUp) {
                                val error = (result as? DohResult.Failure)?.failure?.toProtectionError()
                                    ?: ProtectionError.DNS_UNREACHABLE
                                logStartupDebug(
                                    "dnsVerify givingUp attempts=$probeAttempts budgetExhausted=$budgetExhausted"
                                )
                                requestFailOpen(error)
                            } else {
                                val delay = StartupVerificationPolicy.nextRetryDelayMillis(
                                    PROBE_RETRY_DELAY_MILLIS,
                                    startupBudget?.remainingMillis() ?: PROBE_RETRY_DELAY_MILLIS
                                )
                                if (delay <= 0L) {
                                    requestFailOpen(ProtectionError.DNS_TIMEOUT)
                                } else {
                                    mainHandler.postDelayed({ verifyDnsRoundTrip() }, delay)
                                }
                            }
                        }
                    }
                }
            }
        } catch (rejected: RejectedExecutionException) {
            verificationInFlight.set(false)
        }
    }

    /** The failure kind only — never the endpoint, an address, or any query content. */
    private fun describeOutcome(result: DohResult?): String = when (result) {
        null -> "no_transport"
        is DohResult.Success -> "ok"
        is DohResult.Failure -> result.failure.name
    }

    private fun probeQuery(): ByteArray = DnsMessageWriter.buildQuery(
        transactionId = random.nextInt(0x10000),
        name = probeHost,
        type = DnsMessageReader.TYPE_A,
        ednsUdpPayloadSize = PROBE_EDNS_PAYLOAD
    )

    /**
     * Fail-open: stop intercepting so the system resolver takes over again, and tell the user the
     * protection stopped. Never leave the user with a broken DNS path.
     */
    private fun requestFailOpen(error: ProtectionError) {
        if (!running) return
        Log.w(TAG, "Failing open: $error")
        stopState = ProtectionStateRules.afterFailure()
        stopError = error
        notifications.postStopped(
            getString(R.string.notification_stopped_title),
            getString(R.string.notification_stopped_text, errorText(error))
        )
        mainHandler.post { stopSelf() }
    }

    private fun errorText(error: ProtectionError): String = getString(
        when (error) {
            ProtectionError.TUN_ESTABLISH_FAILED -> R.string.protection_error_tun
            ProtectionError.NO_UNDERLYING_NETWORK -> R.string.protection_error_network
            ProtectionError.ENDPOINT_NOT_DOH -> R.string.protection_error_endpoint
            ProtectionError.DNS_UNREACHABLE -> R.string.protection_error_unreachable
            ProtectionError.DNS_TIMEOUT -> R.string.protection_error_timeout
            ProtectionError.TLS_FAILURE -> R.string.protection_error_tls
            ProtectionError.DNS_INVALID_RESPONSE -> R.string.protection_error_response
        }
    )

    // --- lifecycle ---------------------------------------------------------------------------

    override fun onRevoke() {
        Log.w(TAG, "VPN authorization revoked by the system.")
        authorizationRevoked = true
        stopState = ProtectionStateRules.afterAuthorizationRevoked()
        stopError = null
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRevoked(), null)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        teardown()
        val finalState = stopState ?: if (authorizationRevoked) {
            ProtectionStateRules.afterAuthorizationRevoked()
        } else {
            ProtectionStateRules.afterServiceStopped()
        }
        ProtectionStatusHolder.update(finalState, stopError, activeDnsPath)
        stopState = null
        stopError = null
        isRunning = false
        super.onDestroy()
    }

    private fun stopProtectionByUser() {
        stopState = ProtectionStateRules.afterServiceStopped()
        stopError = null
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancelStopped()
        ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStopped(), null)
        stopSelf()
    }

    private fun teardown() {
        if (!running && tunnel == null && workers == null) return
        running = false
        isRunning = false

        // Cancel any pending callbacks, such as probe retries
        mainHandler.removeCallbacksAndMessages(null)

        runCatching { tunnelOutput?.flush() }
        // Closing the tunnel descriptor unblocks the reader thread.
        runCatching { tunnel?.close() }
        tunnel = null
        tunnelInput = null
        tunnelOutput = null

        val transportToClose = transport
        transport = null
        if (transportToClose != null) {
            Thread({ runCatching { transportToClose.close() } }, "kuies-doh-teardown").start()
        }

        workers?.shutdownNow()
        workers = null

        val reader = readerThread
        readerThread = null
        if (reader != null && reader.isAlive) {
            Thread({ runCatching { reader.join(READER_JOIN_MILLIS) } }, "kuies-reader-join").start()
        }

        unregisterNetworkCallback()
        unregisterPrivateDnsObserver()
        appliedDeclaration = null
        // Nothing about the upstream survives a stop: a late event must not re-declare a network,
        // and the next start has to look again.
        startupUnderlying = null
        appliedUnderlying = null
        detectedUnderlying.clear()
        probeAttempts = 0
        startupBudget = null
        startupTimeline = null
        verificationInFlight.set(false)
        consecutiveFailures.set(0)
        DnsStatsRepository.persistNow()
    }

    private fun unregisterNetworkCallback() {
        val manager = connectivity
        val callback = networkCallback
        networkCallback = null
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    private fun registerPrivateDnsObserver() {
        unregisterPrivateDnsObserver()
        val observer = privateDnsObserverCallback
        try {
            // One observer on both keys: the mode and the specifier can change together.
            PrivateDnsSettings.observedUris().forEach { uri ->
                contentResolver.registerContentObserver(uri, false, observer)
            }
            privateDnsObserver = observer
        } catch (exception: Exception) {
            // A device that refuses the registration simply falls back to re-reading the setting on
            // the next start; nothing about the running tunnel depends on this observer.
            Log.w(TAG, "Could not watch the Private DNS setting: ${exception.javaClass.simpleName}")
        }
    }

    private fun unregisterPrivateDnsObserver() {
        val observer = privateDnsObserver
        privateDnsObserver = null
        if (observer != null) {
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
    }

    /**
     * Re-reads Android's Private DNS setting and reacts only to a real change in the tunnel
     * declaration. A repeated notification, a late callback after a stop, or a mere rename of the
     * strict hostname all leave the running tunnel untouched.
     */
    private fun handlePrivateDnsChange() {
        if (!running) return
        val privateDns = PrivateDnsSettings.read(this)
        val mode = DnsExperiment.mode(this, privateDns.isStrictHostname)
        if (mode == DnsExperimentMode.E1_REAL_DOT_ADDRESS) {
            // E1 needs a blocking bootstrap to know its declaration; leave it alone rather than run
            // a network lookup from a settings callback.
            logStartupDebug(
                "dnsDiag stage=change action=SKIP reason=e1Experiment generation=$tunnelGeneration"
            )
            return
        }
        val plan = VpnRoutePlan.DEFAULT
        val candidate = DnsExperimentPlanner.declarationFor(mode, plan, realDotAddress = null)
        val decision = PrivateDnsCoexistencePolicy.decide(appliedDeclaration, candidate)
        logStartupDebug(
            PrivateDnsCoexistencePolicy.diagnostic(
                privateDnsMode = privateDns.mode.name,
                applied = appliedDeclaration?.kind(plan.dnsServerAddress),
                candidate = candidate?.kind(plan.dnsServerAddress),
                decision = decision,
                generation = tunnelGeneration
            )
        )
        if (decision.change == PrivateDnsChange.RESTART_TUNNEL) {
            Log.i(TAG, "Private DNS mode changed; re-establishing the tunnel with a new declaration.")
            restartTunnel()
        }
    }

    /**
     * Re-establishes the tunnel with the declaration the current setting needs. It reuses the tested
     * teardown/start paths, so pending probes and workers are cancelled first. It only runs while
     * protection is on, and ACTION_STOP still wins because a stop clears the running flag before
     * this can be reached.
     */
    private fun restartTunnel() {
        if (!running) return
        teardown()
        startProtection()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VpnNotifications.ONGOING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(VpnNotifications.ONGOING_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "KuiesVpnService"
        const val ACTION_START = "tw.kuies.block.action.VPN_START"
        const val ACTION_STOP = "tw.kuies.block.action.VPN_STOP"
        private const val DEFAULT_MTU = 1500
        private const val FAILURE_THRESHOLD = 5
        private const val PROBE_ATTEMPTS = 3
        private const val PROBE_EDNS_PAYLOAD = 4096
        private const val PROBE_RETRY_DELAY_MILLIS = 1_500L

        /**
         * Hard ceiling for one start, from STARTING to a decision. Bounds the wait regardless of
         * attempts or network flaps; on expiry the service fails open with a timeout.
         */
        private const val STARTUP_TOTAL_TIMEOUT_MILLIS = 15_000L
        private const val READER_JOIN_MILLIS = 1_000L
        private const val WORKER_THREADS = 8
        private const val QUEUE_CAPACITY = 256
        private const val IDLE_THREAD_SECONDS = 30L

        /**
         * The Private-DNS investigation is finished, so the address-bearing drop detail and the
         * E1 address log are switched off. They were debug-only anyway; this keeps even a debug
         * build from writing addresses by default.
         */
        private const val LOG_DROP_ENDPOINTS = false

        /** Keeps the debug dedup set bounded even if many distinct flows are dropped. */
        private const val MAX_LOGGED_DROP_DETAILS = 128

        /**
         * Coalesces the burst of notifications a single Private DNS change can produce (the mode and
         * the specifier, sometimes more than once). Short enough to feel immediate, long enough that
         * one change is evaluated once.
         */
        private const val PRIVATE_DNS_CHANGE_DEBOUNCE_MILLIS = 400L

        @Volatile
        var isRunning: Boolean = false
            internal set

        /** Starts the tunnel. Only call after `VpnService.prepare()` has been granted. */
        fun start(context: Context) {
            val intent = Intent(context, KuiesVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the tunnel and removes its ongoing notification. */
        fun stop(context: Context) {
            val intent = Intent(context, KuiesVpnService::class.java).setAction(ACTION_STOP)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (exception: Exception) {
                Log.w(TAG, "Could not send ACTION_STOP via startForegroundService: ${exception.message}")
            }
            context.stopService(Intent(context, KuiesVpnService::class.java))
        }
    }
}
