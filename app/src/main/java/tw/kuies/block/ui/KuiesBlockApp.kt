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
import tw.kuies.block.dns.DnsConfigStore
import tw.kuies.block.dns.DnsDiagnosticReport
import tw.kuies.block.dns.DnsDiagnostics
import tw.kuies.block.doh.PlainDnsBootstrap
import tw.kuies.block.doh.VpnSocketProtector
import tw.kuies.block.vpn.KuiesVpnService
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
    // Read once: with the transport picker gone there is nothing on screen that changes it. The
    // stored configuration still drives the tunnel and the diagnostic below.
    val dnsConfig = remember { dnsStore.load() }
    var dnsReport by remember { mutableStateOf(DnsDiagnosticReport.notChecked(dnsConfig.host, dnsConfig.protocol)) }
    var dnsChecking by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        DnsStatsRepository.attach(appContext)
        onDispose { executor.shutdownNow() }
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        settingsStore.setVpnAuthorizationGranted(granted)
        ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRequest(granted))
        if (granted) {
            ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStarting(), null)
            KuiesVpnService.start(appContext)
        }
    }

    fun startRequested() {
        if (VpnAuthorization.isGranted(appContext)) {
            ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStarting(), null)
            KuiesVpnService.start(appContext)
            return
        }
        val consent = VpnAuthorization.consentIntent(appContext)
        if (consent == null) {
            settingsStore.setVpnAuthorizationGranted(true)
            ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStarting(), null)
            KuiesVpnService.start(appContext)
        } else {
            vpnConsentLauncher.launch(consent)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startRequested() }

    fun syncEnvironment() {
        val granted = VpnAuthorization.isGranted(appContext)
        settingsStore.setVpnAuthorizationGranted(granted)
        DnsStatsRepository.refresh()
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
            onRunDnsDiagnostics = { runDnsDiagnostics() },
            onBack = { destination = BlockDestination.HOME },
            modifier = modifier
        )
    }
}
