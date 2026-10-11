package tw.kuies.block.vpn

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import tw.kuies.block.R
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.core.ProtectionStatusHolder
import tw.kuies.block.data.DnsStatsRepository
import tw.kuies.block.dns.DnsConfig
import tw.kuies.block.dns.DnsConfigStore
import tw.kuies.block.dns.DnsProtocol
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
import tw.kuies.block.doh.OkHttpDohTransport
import tw.kuies.block.doh.PlainDnsBootstrap
import tw.kuies.block.doh.VpnSocketProtector
import tw.kuies.block.doh.toProtectionError

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
    private var underlyingNetwork: Network? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        return START_STICKY
    }

    // --- start -------------------------------------------------------------------------------

    private fun startProtection() {
        running = true
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
        val underlying = UnderlyingNetworkFinder.find(connectivity)
        if (underlying == null) {
            Log.w(TAG, "No usable non-VPN network for the DoH bootstrap.")
            requestFailOpen(ProtectionError.NO_UNDERLYING_NETWORK)
            return
        }
        underlyingNetwork = underlying.network
        setUnderlyingNetworks(arrayOf(underlying.network))

        // Validate the transport before creating a tunnel we could not serve.
        val created = createTransport(underlying.dnsServers)
        if (created == null) {
            requestFailOpen(ProtectionError.ENDPOINT_NOT_DOH)
            return
        }
        transport = created

        val plan = VpnRoutePlan.DEFAULT
        val problems = VpnRoutePlanPolicy.problemsWith(plan)
        if (problems.isNotEmpty()) {
            Log.e(TAG, "Refusing an unsafe route plan: ${problems.joinToString()}")
            requestFailOpen(ProtectionError.TUN_ESTABLISH_FAILED)
            return
        }
        routePlan = plan

        val descriptor = establishTunnel(plan)
        if (descriptor == null) {
            requestFailOpen(ProtectionError.TUN_ESTABLISH_FAILED)
            return
        }
        tunnel = descriptor
        tunnelInput = FileInputStream(descriptor.fileDescriptor)
        tunnelOutput = FileOutputStream(descriptor.fileDescriptor)
        workers = newWorkerPool()
        // Re-assert the underlying network now that the tunnel exists.
        underlyingNetwork?.let { setUnderlyingNetworks(arrayOf(it)) }

        registerUnderlyingNetworkCallback()
        startReaderThread(plan)
        verifyDnsRoundTrip()
    }

    private fun establishTunnel(plan: VpnRoutePlan): ParcelFileDescriptor? = try {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(plan.mtu)
            .addAddress(plan.interfaceAddress, plan.interfacePrefixLength)
            .addDnsServer(plan.dnsServerAddress)
            .addRoute(plan.dnsServerAddress, plan.dnsPrefixLength)
            .setBlocking(true)
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
        return OkHttpDohTransport(
            endpoint = endpoint,
            protector = protector,
            resolver = PlainDnsBootstrap(protector, dnsServers)
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

    private fun registerUnderlyingNetworkCallback() {
        val manager = connectivity ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onUnderlyingNetworkChanged(network, manager.getLinkProperties(network))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                onUnderlyingNetworkChanged(network, linkProperties)
            }
        }
        try {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (exception: Exception) {
            Log.w(TAG, "Could not watch the underlying network: ${exception.javaClass.simpleName}")
        }
    }

    private fun onUnderlyingNetworkChanged(network: Network, linkProperties: LinkProperties?) {
        val servers = UnderlyingNetworkFinder.dnsServersOf(linkProperties)
        mainHandler.post {
            if (!running) return@post
            val previous = underlyingNetwork
            underlyingNetwork = network
            setUnderlyingNetworks(arrayOf(network))
            if (servers.isEmpty()) return@post
            // A Wi-Fi/mobile switch invalidates the old connections, so rebuild the transport.
            rebuildTransport(servers)
            if (previous != network) {
                Log.i(TAG, "Underlying network changed; re-bootstrapping DoH.")
            }
        }
    }

    private fun rebuildTransport(dnsServers: List<InetSocketAddress>) {
        transport?.close()
        transport = createTransport(dnsServers)
        if (transport == null) {
            requestFailOpen(ProtectionError.ENDPOINT_NOT_DOH)
            return
        }
        if (ProtectionStatusHolder.state == ProtectionState.STARTING) {
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
                    is DnsTunnelOutcome.Drop -> logDropOnce(outcome.reason)
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
        executor.execute {
            val result = transport?.exchange(probeQuery())
            mainHandler.post {
                if (!running) return@post
                when (result) {
                    is DohResult.Success -> {
                        consecutiveFailures.set(0)
                        ProtectionStatusHolder.update(ProtectionStateRules.afterDnsVerified())
                        notifications.updateOngoing(
                            getString(R.string.notification_active_title),
                            getString(R.string.notification_active_text)
                        )
                        Log.i(TAG, "DNS round trip verified; protection is active.")
                    }
                    is DohResult.Failure, null -> {
                        probeAttempts++
                        if (probeAttempts >= PROBE_ATTEMPTS) {
                            val error = (result as? DohResult.Failure)?.failure?.toProtectionError()
                                ?: ProtectionError.DNS_UNREACHABLE
                            requestFailOpen(error)
                        } else {
                            mainHandler.postDelayed({ verifyDnsRoundTrip() }, PROBE_RETRY_DELAY_MILLIS)
                        }
                    }
                }
            }
        }
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
        ProtectionStatusHolder.update(finalState, stopError)
        stopState = null
        stopError = null
        super.onDestroy()
    }

    private fun teardown() {
        if (!running && tunnel == null) return
        running = false
        runCatching { tunnelOutput?.flush() }
        // Closing the tunnel descriptor unblocks the reader thread.
        runCatching { tunnel?.close() }
        tunnel = null
        tunnelInput = null
        tunnelOutput = null

        runCatching { transport?.close() }
        transport = null
        workers?.shutdownNow()
        workers = null
        runCatching { readerThread?.join(READER_JOIN_MILLIS) }
        readerThread = null

        unregisterNetworkCallback()
        underlyingNetwork = null
        probeAttempts = 0
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
        private const val ACTION_START = "tw.kuies.block.action.VPN_START"
        private const val DEFAULT_MTU = 1500
        private const val FAILURE_THRESHOLD = 5
        private const val PROBE_ATTEMPTS = 3
        private const val PROBE_EDNS_PAYLOAD = 4096
        private const val PROBE_RETRY_DELAY_MILLIS = 1_500L
        private const val READER_JOIN_MILLIS = 1_000L
        private const val WORKER_THREADS = 8
        private const val QUEUE_CAPACITY = 256
        private const val IDLE_THREAD_SECONDS = 30L

        /** Starts the tunnel. Only call after `VpnService.prepare()` has been granted. */
        fun start(context: Context) {
            val intent = Intent(context, KuiesVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the tunnel and removes its ongoing notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, KuiesVpnService::class.java))
        }
    }
}
