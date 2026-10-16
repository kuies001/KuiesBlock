package tw.kuies.block.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.core.ProtectionStatusHolder
import tw.kuies.block.data.AppSettingsStore
import tw.kuies.block.data.DnsStatsRepository
import tw.kuies.block.data.OnboardingGate
import tw.kuies.block.dns.DnsConfigStore
import tw.kuies.block.dns.DnsDiagnosticReport
import tw.kuies.block.dns.DnsDiagnostics
import tw.kuies.block.dns.PrivateDnsSettings
import tw.kuies.block.doh.PlainDnsBootstrap
import tw.kuies.block.doh.VpnSocketProtector
import tw.kuies.block.filter.FilterCoordinatorHolder
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSettingsStore
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.vpn.KuiesVpnService
import tw.kuies.block.vpn.OtherVpnProbe
import tw.kuies.block.vpn.UnderlyingNetworkFinder
import tw.kuies.block.vpn.VpnAuthorization
import java.net.InetAddress
import java.util.concurrent.Executors

private const val TAG = "KuiesBlockApp"

private enum class BlockDestination { HOME, SETTINGS }

@Composable
fun KuiesBlockApp(
    lifecycleOwner: LifecycleOwner,
    appVersion: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settingsStore = remember(appContext) { AppSettingsStore(appContext) }
    val dnsStore = remember(appContext) { DnsConfigStore(appContext) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var destination by remember { mutableStateOf(BlockDestination.HOME) }

    // The first-run decision is made once, from what is already stored, and before this session
    // writes anything of its own: `syncEnvironment()` records the platform's authorization answer on
    // the first resume, so asking later would make every install look used. The guide is only for a
    // genuinely new one — see [OnboardingGate.shouldShowAutomatically] for the four facts it needs.
    val guideOnFirstRun = remember {
        OnboardingGate.shouldShowAutomatically(
            settings = settingsStore.load(),
            filterSettingsStored = FilterSettingsStore(appContext).hasStoredSettings()
        )
    }
    // Kept across a configuration change: rotating the phone in the middle of the guide must not
    // drop the user on the home screen, which is also why the page and the replay flag are saved.
    var guideVisible by rememberSaveable { mutableStateOf(guideOnFirstRun) }
    var guidePage by rememberSaveable { mutableStateOf(OnboardingPage.VPN_PERMISSION) }
    var guideReplay by rememberSaveable { mutableStateOf(false) }

    // Whether the authorization is really held, refreshed by the same signal the rest of the screen
    // uses (the authoritative `prepare()` check on every resume, plus the consent dialog's result).
    var vpnAuthorized by remember { mutableStateOf(false) }

    // Whether another app's VPN owns the system's default network. Shown as a plain notice only.
    var anotherVpnActive by remember { mutableStateOf(false) }

    // Read once: with the transport picker gone there is nothing on screen that changes it. The
    // stored configuration still drives the tunnel and the diagnostic below.
    val dnsConfig = remember { dnsStore.load() }
    var dnsReport by remember { mutableStateOf(DnsDiagnosticReport.notChecked(dnsConfig.host, dnsConfig.protocol)) }
    var dnsChecking by remember { mutableStateOf(false) }

    // Null until the coordinator has read the stored settings, so the strength list marks no choice
    // rather than guessing one.
    var filterStatus by remember { mutableStateOf<FilterStatus?>(null) }
    var filterBusy by remember { mutableStateOf(false) }

    // A manual update is separate from choosing a strength: it re-downloads the list that is already
    // in use, so the two must not share one flag and make the screen describe the wrong action.
    var filterUpdating by remember { mutableStateOf(false) }

    // Which custom source is downloading right now, if any. Per source on purpose: one busy row must
    // not make every other row look like it is working.
    var subscriptionBusyId by remember { mutableStateOf<String?>(null) }

    // Private DNS mode as state, not a value read during composition: only a state write can
    // recompose, so reading it at the call site left the strength section describing a mode the user
    // had already changed outside the app.
    var privateDnsMode by remember { mutableStateOf(PrivateDnsSettings.read(appContext).mode) }

    DisposableEffect(Unit) {
        DnsStatsRepository.attach(appContext)
        // Publish what is already stored — no network — so the settings screen shows the real state
        // the first time it is opened, and so a filter that was switched on earlier is in effect
        // without waiting for the tunnel.
        FilterCoordinatorHolder.get(appContext).publishCurrentAsync { status ->
            mainHandler.post { filterStatus = status }
        }
        onDispose { executor.shutdownNow() }
    }

    /**
     * Whether the consent dialog is being opened to start protection, or only to record the
     * authorization.
     *
     * The guide's first page asks for the permission and nothing else — its second page is where
     * protection is started — while the home switch and the guide's second page want the start. One
     * launcher and one flow serve both, so this is the only thing that differs.
     */
    var consentStartsProtection by remember { mutableStateOf(true) }

    /**
     * Starts the tunnel through the app's one start path.
     *
     * Records that protection was started on this install, once and for all: that is the quietest and
     * most reliable sign the install is not a new one, and it is what lets the first-run guide stay
     * away after the user later revokes the VPN authorization.
     */
    fun startTunnel() {
        settingsStore.setProtectionStarted()
        ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStarting(), null)
        KuiesVpnService.start(appContext)
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Only an explicit OK is a grant: a refusal is recorded as not authorized and never starts
        // anything, so the guide keeps asking and never shows a permission it does not hold.
        val granted = VpnAuthorization.consentGranted(result.resultCode)
        val alsoStart = consentStartsProtection
        consentStartsProtection = true
        vpnAuthorized = granted
        settingsStore.setVpnAuthorizationGranted(granted)
        ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRequest(granted))
        if (granted && alsoStart) {
            startTunnel()
        }
        // Page 1 only wanted the permission: a grant it obtained moves the guide on.
        if (granted && !alsoStart && guideVisible && guidePage == OnboardingPage.VPN_PERMISSION) {
            guidePage = OnboardingPage.START_PROTECTION
        }
    }

    fun startRequested() {
        if (VpnAuthorization.isGranted(appContext)) {
            vpnAuthorized = true
            startTunnel()
            return
        }
        val consent = VpnAuthorization.consentIntent(appContext)
        if (consent == null) {
            settingsStore.setVpnAuthorizationGranted(true)
            vpnAuthorized = true
            startTunnel()
        } else {
            consentStartsProtection = true
            vpnConsentLauncher.launch(consent)
        }
    }

    /**
     * The guide's first page: asks for the VPN authorization and records the answer, without starting
     * protection. Reuses the same launcher and the same [VpnAuthorization] call as the home switch.
     */
    fun requestVpnAuthorizationOnly() {
        val consent = VpnAuthorization.consentIntent(appContext)
        if (consent == null) {
            // Already granted between the two checks: record it and move on, ask nothing.
            settingsStore.setVpnAuthorizationGranted(true)
            vpnAuthorized = true
            ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRequest(true))
            guidePage = OnboardingPage.START_PROTECTION
            return
        }
        consentStartsProtection = false
        vpnConsentLauncher.launch(consent)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startRequested() }

    fun syncEnvironment() {
        val granted = VpnAuthorization.isGranted(appContext)
        vpnAuthorized = granted
        settingsStore.setVpnAuthorizationGranted(granted)
        DnsStatsRepository.refresh()
        // Re-read on every resume: the strength section must never describe a Private DNS mode the
        // user has already changed in Android's settings and come back from.
        privateDnsMode = PrivateDnsSettings.read(appContext).mode
        // Read-only probe for the guide's plain notice; it never stops or reconfigures that VPN.
        anotherVpnActive = OtherVpnProbe.anotherVpnHoldsTheSlot(appContext, KuiesVpnService.isRunning)
        when {
            !granted -> ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRevoked())
            ProtectionStatusHolder.state == ProtectionState.NOT_AUTHORIZED ->
                ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRequest(true))
        }
    }

    fun runDnsDiagnostics() {
        if (dnsChecking) return
        dnsChecking = true
        val config = dnsConfig
        executor.execute {
            val report = try {
                // Resolve through the same protected bootstrap the DoH client uses, never through the
                // system resolver that Android's Private DNS takes over. The addresses stay inside
                // this call: the report carries outcomes only.
                val servers = UnderlyingNetworkFinder.find(appContext)?.dnsServers.orEmpty()
                val resolver: (String) -> List<InetAddress> =
                    PlainDnsBootstrap(VpnSocketProtector.NONE, servers)::resolve
                DnsDiagnostics(config, resolver).run()
            } catch (exception: Exception) {
                Log.w(TAG, "DNS diagnostics failed: ${exception.javaClass.simpleName}")
                DnsDiagnosticReport.failed(config.host, config.protocol, System.currentTimeMillis())
            }
            mainHandler.post {
                dnsReport = report
                dnsChecking = false
            }
        }
    }

    /**
     * Applies a strength choice.
     *
     * There is no "off" choice to pass any more: turning protection off is the home switch's job, and
     * picking a tier only chooses which list is used — it never starts the tunnel.
     *
     * The coordinator calls back twice: immediately with the state that needs no network, then once
     * the download settled. Only the second callback clears the busy flag, so the screen cannot claim
     * the download finished while it is still running.
     */
    fun selectFilterStrength(profile: FilterProfile) {
        filterBusy = true
        FilterCoordinatorHolder.get(appContext).selectStrength(
            profile = profile,
            onStatus = { status -> mainHandler.post { filterStatus = status } },
            onFinished = { mainHandler.post { filterBusy = false } }
        )
    }

    /**
     * Re-downloads the list that is already in use, on the user's explicit request.
     *
     * A failure keeps the previous list and reports itself through the status, and nothing here can
     * leave the device without DNS: the download never runs on the tunnel's packet path.
     */
    fun updateFilterNow() {
        if (filterUpdating) return
        filterUpdating = true
        FilterCoordinatorHolder.get(appContext).refresh { status ->
            mainHandler.post {
                filterStatus = status
                filterUpdating = false
            }
        }
    }

    /**
     * Adds or removes one of the user's own rules.
     *
     * Local only: the coordinator writes the settings and republishes the snapshot on its own thread,
     * so a running tunnel picks the change up on its next query and nothing is downloaded.
     */
    fun editManualRule(rule: ManualRule, isAllow: Boolean, add: Boolean) {
        val coordinator = FilterCoordinatorHolder.get(appContext)
        val onStatus: (FilterStatus) -> Unit = { status -> mainHandler.post { filterStatus = status } }
        when {
            isAllow && add -> coordinator.addManualAllow(rule, onStatus)
            isAllow -> coordinator.removeManualAllow(rule.domain, onStatus)
            add -> coordinator.addManualBlock(rule, onStatus)
            else -> coordinator.removeManualBlock(rule.domain, onStatus)
        }
    }

    /**
     * Adds a custom source and fetches it straight away.
     *
     * A source the user just typed in should not sit empty waiting for a second tap, so the download
     * follows immediately; the row shows itself as busy until it settles.
     */
    fun addCustomSubscription(subscription: FilterSubscription) {
        val coordinator = FilterCoordinatorHolder.get(appContext)
        subscriptionBusyId = subscription.id
        coordinator.addSubscription(subscription) { status ->
            mainHandler.post { filterStatus = status }
            coordinator.updateSubscription(subscription) { updated ->
                mainHandler.post {
                    filterStatus = updated
                    subscriptionBusyId = null
                }
            }
        }
    }

    fun setSubscriptionEnabled(subscription: FilterSubscription, enabled: Boolean) {
        FilterCoordinatorHolder.get(appContext).setSubscriptionEnabled(subscription, enabled) { status ->
            mainHandler.post { filterStatus = status }
        }
    }

    fun updateSubscription(subscription: FilterSubscription) {
        if (subscriptionBusyId != null) return
        subscriptionBusyId = subscription.id
        FilterCoordinatorHolder.get(appContext).updateSubscription(subscription) { status ->
            mainHandler.post {
                filterStatus = status
                subscriptionBusyId = null
            }
        }
    }

    fun deleteSubscription(subscription: FilterSubscription) {
        FilterCoordinatorHolder.get(appContext).removeSubscription(subscription.id) { status ->
            mainHandler.post { filterStatus = status }
        }
    }

    fun onToggleProtection() {
        when (ProtectionStatusHolder.state) {
            ProtectionState.ACTIVE, ProtectionState.STARTING -> {
                KuiesVpnService.stop(appContext)
                ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStopped(), null)
            }
            ProtectionState.ERROR -> {
                if (KuiesVpnService.isRunning) {
                    KuiesVpnService.stop(appContext)
                    ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStopped(), null)
                } else {
                    startRequested()
                }
            }
            ProtectionState.NOT_AUTHORIZED -> {
                val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsNotificationPermission) {
                    // The notification is informational: continue to the VPN flow either way.
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startRequested()
                }
            }
            ProtectionState.AUTHORIZED_IDLE -> startRequested()
        }
    }

    LaunchedEffect(appContext) {
        // Show the last known state if not already started/errored, then correct with authoritative check.
        if (ProtectionStatusHolder.state == ProtectionState.NOT_AUTHORIZED) {
            ProtectionStatusHolder.update(
                ProtectionStateRules.afterAuthorizationRequest(settingsStore.load().vpnAuthorizationGranted)
            )
        }
        syncEnvironment()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncEnvironment()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val protectionState = ProtectionStatusHolder.state
    val protectionError = ProtectionStatusHolder.error

    fun leaveGuide(completed: Boolean) {
        // Read before it is cleared: a replay returns to settings, a first run to the home screen.
        val replay = guideReplay
        // A replay records nothing: the install's own record must be left exactly as it was, so a
        // guide opened again from settings can never make the automatic one come back.
        OnboardingGate.stateToPersist(replay = replay, completed = completed)?.let {
            settingsStore.setOnboardingState(it)
        }
        guideVisible = false
        guideReplay = false
        guidePage = OnboardingPage.VPN_PERMISSION
        destination = if (replay) BlockDestination.SETTINGS else BlockDestination.HOME
    }

    fun onGuidePrimaryAction() {
        when (guidePage) {
            // Page 1 owns the permission only: it asks for it, or moves on when it is already held.
            OnboardingPage.VPN_PERMISSION ->
                if (vpnAuthorized) guidePage = OnboardingPage.START_PROTECTION else requestVpnAuthorizationOnly()

            // Page 2 reports the real state, so its one button does whatever that state needs: finish
            // once protection verifiably runs, wait while it starts, and otherwise hand the tap to the
            // home switch's own handler. That is the same `startRequested()` the home screen uses,
            // plus the app's existing retry/stop decision for a failure — no second state machine.
            OnboardingPage.START_PROTECTION -> when {
                ProtectionStateRules.isInterceptionActive(protectionState) -> leaveGuide(completed = true)
                protectionState == ProtectionState.STARTING -> Unit
                else -> onToggleProtection()
            }
        }
    }

    // The guide covers the app until it is finished or skipped, which is what "show it on first run"
    // means; skipping it returns to the home screen with everything reachable.
    if (guideVisible) {
        OnboardingScreen(
            page = guidePage,
            vpnAuthorized = vpnAuthorized,
            anotherVpnActive = anotherVpnActive,
            protectionState = protectionState,
            protectionError = protectionError,
            isServiceRunning = KuiesVpnService.isRunning,
            onPrimaryAction = { onGuidePrimaryAction() },
            onSkip = { leaveGuide(completed = false) },
            modifier = modifier
        )
        return
    }

    // The home screen renders the running state and nothing about DNS: no status derivation, no
    // counters. `ProtectionStatusHolder.state` is what the service last reported, so a claim of
    // 「防護已啟用」 can only come from the tunnel that really came up and verified its DNS path.
    when (destination) {
        BlockDestination.HOME -> HomeScreen(
            protectionState = protectionState,
            protectionError = protectionError,
            isServiceRunning = KuiesVpnService.isRunning,
            onToggleProtection = { onToggleProtection() },
            onOpenSettings = { destination = BlockDestination.SETTINGS },
            modifier = modifier
        )

        BlockDestination.SETTINGS -> SettingsScreen(
            dnsReport = dnsReport,
            dnsChecking = dnsChecking,
            appVersion = appVersion,
            filterStatus = filterStatus,
            filterBusy = filterBusy,
            filterUpdating = filterUpdating,
            subscriptionBusyId = subscriptionBusyId,
            privateDnsMode = privateDnsMode,
            // The real state, not a yes/no: the strength section tells a fresh install, a start in
            // progress, a working tunnel and a failure apart — see FilterPresentation.stateLabel.
            protectionState = protectionState,
            isServiceRunning = KuiesVpnService.isRunning,
            onSelectStrength = { profile -> selectFilterStrength(profile) },
            onUpdateFilterNow = { updateFilterNow() },
            onAddManualRule = { rule, isAllow -> editManualRule(rule, isAllow, add = true) },
            onRemoveManualRule = { rule, isAllow -> editManualRule(rule, isAllow, add = false) },
            onAddSubscription = { subscription -> addCustomSubscription(subscription) },
            onSetSubscriptionEnabled = { subscription, enabled ->
                setSubscriptionEnabled(subscription, enabled)
            },
            onUpdateSubscription = { subscription -> updateSubscription(subscription) },
            onDeleteSubscription = { subscription -> deleteSubscription(subscription) },
            onRunDnsDiagnostics = { runDnsDiagnostics() },
            onReplayGuide = {
                // A replay: the two pages are shown over settings, and leaving them records nothing
                // and changes no stored value, so the guide can never come back by itself from here.
                guidePage = OnboardingPage.VPN_PERMISSION
                guideReplay = true
                guideVisible = true
            },
            onBack = { destination = BlockDestination.HOME },
            modifier = modifier
        )
    }
}
