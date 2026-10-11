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
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsConfigStore
import tw.kuies.block.dns.DnsDiagnosticReport
import tw.kuies.block.dns.DnsDiagnostics
import tw.kuies.block.dns.DnsProtocol
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.dns.PrivateDnsSettings
import tw.kuies.block.vpn.KuiesVpnService
import tw.kuies.block.vpn.VpnAuthorization
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
    var dnsConfig by remember { mutableStateOf(dnsStore.load()) }
    var dnsReport by remember { mutableStateOf(DnsDiagnosticReport.notChecked(dnsConfig.host, dnsConfig.protocol)) }
    var dnsChecking by remember { mutableStateOf(false) }
    var privateDnsMode by remember { mutableStateOf(PrivateDnsMode.UNKNOWN) }

    DisposableEffect(Unit) {
        DnsStatsRepository.attach(appContext)
        onDispose { executor.shutdown() }
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        settingsStore.setVpnAuthorizationGranted(granted)
        ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRequest(granted))
        if (granted) {
            KuiesVpnService.start(appContext)
        }
    }

    fun startRequested() {
        if (VpnAuthorization.isGranted(appContext)) {
            KuiesVpnService.start(appContext)
            return
        }
        val consent = VpnAuthorization.consentIntent(appContext)
        if (consent == null) {
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
        privateDnsMode = PrivateDnsSettings.read(appContext)
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
                DnsDiagnostics(config).run()
            } catch (exception: Exception) {
                Log.w(TAG, "DNS diagnostics failed: ${exception.javaClass.simpleName}")
                DnsDiagnosticReport(
                    host = config.host,
                    protocol = config.protocol,
                    resolvedAddresses = emptyList(),
                    resolutionError = exception.javaClass.simpleName,
                    probes = emptyList(),
                    status = DnsCheckStatus.ERROR,
                    checkedAtMillis = System.currentTimeMillis()
                )
            }
            mainHandler.post {
                dnsReport = report
                dnsChecking = false
            }
        }
    }

    fun onToggleProtection() {
        when (ProtectionStatusHolder.state) {
            ProtectionState.ACTIVE, ProtectionState.STARTING -> KuiesVpnService.stop(appContext)
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
            ProtectionState.AUTHORIZED_IDLE, ProtectionState.ERROR -> startRequested()
        }
    }

    LaunchedEffect(appContext) {
        // Show the last known state immediately, then correct it with the authoritative check.
        ProtectionStatusHolder.update(
            ProtectionStateRules.afterAuthorizationRequest(settingsStore.load().vpnAuthorizationGranted)
        )
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
    val blockStats = DnsStatsRepository.today
    val dnsStatus = when {
        dnsChecking -> DnsCheckStatus.CHECKING
        ProtectionStateRules.isInterceptionActive(protectionState) -> DnsCheckStatus.OK
        protectionState == ProtectionState.ERROR -> DnsCheckStatus.ERROR
        else -> dnsReport.status
    }

    when (destination) {
        BlockDestination.HOME -> HomeScreen(
            protectionState = protectionState,
            protectionError = protectionError,
            blockStats = blockStats,
            dnsStatus = dnsStatus,
            dnsHost = dnsConfig.host,
            privateDnsMode = privateDnsMode,
            dnsChecking = dnsChecking,
            onToggleProtection = { onToggleProtection() },
            onRunDnsDiagnostics = { runDnsDiagnostics() },
            onOpenSettings = { destination = BlockDestination.SETTINGS },
            modifier = modifier
        )

        BlockDestination.SETTINGS -> SettingsScreen(
            dnsConfig = dnsConfig,
            dnsReport = dnsReport,
            dnsChecking = dnsChecking,
            blockStats = blockStats,
            privateDnsMode = privateDnsMode,
            appVersion = appVersion,
            onSelectProtocol = { protocol ->
                val updated = dnsConfig.copy(protocol = protocol)
                dnsConfig = updated
                dnsStore.save(updated)
                dnsReport = DnsDiagnosticReport.notChecked(updated.host, updated.protocol)
            },
            onRunDnsDiagnostics = { runDnsDiagnostics() },
            onBack = { destination = BlockDestination.HOME },
            modifier = modifier
        )
    }
}
