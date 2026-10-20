package tw.kuies.block.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import tw.kuies.block.R
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.core.ProtectionStatusHolder
import tw.kuies.block.data.AppExclusionApplyResult
import tw.kuies.block.data.AppExclusionApplyStatus
import tw.kuies.block.data.AppExclusionStore
import tw.kuies.block.data.AppSettingsStore
import tw.kuies.block.data.DnsStatsRepository
import tw.kuies.block.data.InstalledApp
import tw.kuies.block.data.InstalledAppsRepository
import tw.kuies.block.data.OnboardingGate
import tw.kuies.block.data.ProtectionIntentStore
import tw.kuies.block.data.ThemeMode
import tw.kuies.block.dns.PrivateDnsSettings
import tw.kuies.block.filter.FilterAutoUpdateSettings
import tw.kuies.block.filter.FilterAutoUpdateStore
import tw.kuies.block.filter.FilterCoordinatorHolder
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterSettingsStore
import tw.kuies.block.filter.FilterSource
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterUpdateSchedulePolicy
import tw.kuies.block.filter.UpdateInterval
import tw.kuies.block.filter.WorkManagerPeriodicUpdateScheduler
import tw.kuies.block.power.BatteryOptimization
import tw.kuies.block.update.AppVersion
import tw.kuies.block.update.GitHubReleaseSource
import tw.kuies.block.update.ReleaseInfo
import tw.kuies.block.update.UpdateCheckFailure
import tw.kuies.block.update.UpdateCheckOutcome
import tw.kuies.block.update.UpdateChecker
import tw.kuies.block.update.UpdateInstallFailure
import tw.kuies.block.update.UpdateInstallResult
import tw.kuies.block.update.UpdateInstallState
import tw.kuies.block.update.UpdateInstaller
import tw.kuies.block.update.UpdateInstallStatus
import tw.kuies.block.vpn.AlwaysOnVpnProbe
import tw.kuies.block.vpn.KuiesVpnService
import tw.kuies.block.vpn.OtherVpnProbe
import tw.kuies.block.vpn.VpnAuthorization
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private enum class BlockDestination { HOME, SETTINGS, APP_PROTECTION }

@Composable
fun KuiesBlockApp(
    lifecycleOwner: LifecycleOwner,
    appVersion: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settingsStore = remember(appContext) { AppSettingsStore(appContext) }
    val appExclusionStore = remember(appContext) { AppExclusionStore(appContext) }
    val updateStore = remember(appContext) { FilterAutoUpdateStore(appContext) }
    val protectionIntentStore = remember(appContext) { ProtectionIntentStore(appContext) }
    val updateScheduler = remember(appContext) { WorkManagerPeriodicUpdateScheduler(appContext) }
    // The app-update check owns its own client and has no schedule at all, so it shares neither the
    // filter-list worker nor its bounds: see GitHubReleaseSource.
    val updateSource = remember(appContext) { GitHubReleaseSource.create() }
    val updateChecker = remember(appContext) { UpdateChecker(updateSource, AppVersion.parse(appVersion)) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val appListExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var destination by remember { mutableStateOf(BlockDestination.HOME) }

    // The per-app page is reachable from home and from no other screen now, but keeping the return
    // destination explicit means a later entry point cannot strand the back arrow on the wrong page.
    var appProtectionReturnTo by remember { mutableStateOf(BlockDestination.HOME) }

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

    // Null until the coordinator has read the stored settings, so the strength list marks no choice
    // rather than guessing one.
    var filterStatus by remember { mutableStateOf<FilterStatus?>(null) }
    var filterBusy by remember { mutableStateOf(false) }

    // A manual update is separate from choosing a mode: it re-downloads the lists already in use, so
    // the two must not share one flag and make the screen describe the wrong action.
    var filterUpdating by remember { mutableStateOf(false) }

    // Per-app exclusions are a VPN-scope setting, kept separate from FilterSettings. The list is
    // loaded only when the management page is opened, off the main thread.
    var excludedPackages by remember { mutableStateOf(appExclusionStore.load()) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(false) }
    var appsLoadFailed by remember { mutableStateOf(false) }
    var appProtectionStatus by remember { mutableStateOf(AppProtectionStatus.IDLE) }
    var appApplyResult by remember { mutableStateOf<AppExclusionApplyResult?>(null) }

    // The automatic-update preference, or null until it is read.
    var autoUpdate by remember { mutableStateOf<FilterAutoUpdateSettings?>(null) }

    // Boot auto-start: the stored preference, and whether the system's Always-on VPN already owns the
    // restore. Both are read from the platform, never inferred.
    var bootAutostart by remember { mutableStateOf(false) }
    var alwaysOnVpn by remember { mutableStateOf(false) }

    // The DNS backup switch, read once at composition and then kept in step with the store.
    var dnsFallbackEnabled by remember {
        mutableStateOf(settingsStore.load().dnsAutoFallbackEnabled)
    }

    // Whether Android really exempts this app from battery optimisation. Only the platform answers:
    // a stored "the user tapped allow" flag would keep the granted row after the exemption was
    // revoked in Android's settings. Re-read on every resume by syncEnvironment().
    var batteryExemptionGranted by remember {
        mutableStateOf(BatteryOptimization.isExempt(appContext))
    }

    // The manual app-update check: whether one is running, what the last one found, and the release
    // still being offered in a dialog. Kept in memory only — a check is never stored, never scheduled
    // and never runs on its own.
    var updateChecking by remember { mutableStateOf(false) }
    var updateOutcome by remember { mutableStateOf<UpdateCheckOutcome?>(null) }
    var pendingUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateInstallState by remember { mutableStateOf<UpdateInstallState>(UpdateInstallState.Idle) }
    var verifiedUpdateFile by remember { mutableStateOf<File?>(null) }
    val updateInstallerScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    val installFailure by UpdateInstallStatus.failure.collectAsState()
    LaunchedEffect(installFailure) {
        installFailure?.let { failure ->
            updateInstallState = UpdateInstallState.Failed(failure)
            UpdateInstallStatus.clear()
        }
    }

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
        // Read the stored preferences once and make the platform agree with them, so an app update or a
        // restore leaves the user's interval in force instead of losing the schedule.
        val storedAutoUpdate = updateStore.load()
        autoUpdate = storedAutoUpdate
        bootAutostart = protectionIntentStore.bootAutostartEnabled()
        FilterUpdateSchedulePolicy.apply(storedAutoUpdate, updateScheduler)
        onDispose {
            // A download belongs to this composition: leaving it closes the HTTP call and lets the
            // downloader remove its partial file. A completed APK stays only until the next check.
            updateJob?.cancel()
            updateInstallerScope.cancel()
            // The update source built its own client, so its pool and its workers are released here.
            updateSource.close()
            executor.shutdownNow()
            appListExecutor.shutdownNow()
        }
    }

    DisposableEffect(Unit) {
        val remove = AppExclusionApplyStatus.addListener { result ->
            mainHandler.post { appApplyResult = result }
        }
        onDispose { remove() }
    }

    LaunchedEffect(appApplyResult) {
        when (val result = appApplyResult) {
            is AppExclusionApplyResult.Applied ->
                if (result.packages == excludedPackages) {
                    appProtectionStatus = AppProtectionStatus.APPLIED
                }
            is AppExclusionApplyResult.Failed -> appProtectionStatus = AppProtectionStatus.FAILED
            null -> Unit
        }
    }

    LaunchedEffect(destination) {
        if (destination != BlockDestination.APP_PROTECTION) return@LaunchedEffect
        appsLoading = true
        appsLoadFailed = false
        appListExecutor.execute {
            val result = InstalledAppsRepository.load(appContext)
            mainHandler.post {
                installedApps = result.apps
                appsLoadFailed = result.failed
                appsLoading = false
            }
        }
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
        // The user's last word on protection. A restart may only come back when it was "on" — this is
        // what keeps a deliberate "turn protection off" from being undone at the next boot.
        protectionIntentStore.setProtectionDesiredOn(true)
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

    /**
     * Opens Android's per-app "install unknown apps" page. No permission is granted from here; the
     * user's answer is read again on resume.
     */
    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { appContext.startActivity(intent) }.isFailure) {
            updateInstallState = UpdateInstallState.Failed(UpdateInstallFailure.INSTALLER_UNAVAILABLE)
        }
    }

    /**
     * Writes the verified APK into a PackageInstaller session. Android's own installer owns the
     * confirmation UI, so no implicit APK view intent can offer a third-party installer.
     */
    fun launchUpdateInstaller(file: File) {
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            updateInstallState = UpdateInstallState.NeedsInstallPermission
            openInstallPermissionSettings()
            return
        }
        updateInstallState = UpdateInstallState.Installing
        UpdateInstallStatus.clear()
        updateInstallerScope.launch {
            val failure = UpdateInstaller.commitSession(appContext, file)
            mainHandler.post {
                updateInstallState = if (failure == null) {
                    UpdateInstallState.Ready
                } else {
                    UpdateInstallState.Failed(failure)
                }
            }
        }
    }

    /**
     * Downloads and verifies the release in the background, then opens the system installer once.
     *
     * The job is owned by this screen's scope rather than the dialog, so rotating the phone or leaving
     * Settings does not cancel an in-progress download. The user can cancel explicitly; the job then
     * closes the HTTP call and removes the partial file.
     */
    fun startUpdateDownload(release: ReleaseInfo) {
        if (updateJob?.isActive == true) return
        verifiedUpdateFile = null
        UpdateInstallStatus.clear()
        updateInstallState = UpdateInstallState.Downloading(null)
        updateJob = updateInstallerScope.launch {
            try {
                val result = UpdateInstaller.downloadAndVerify(
                    context = appContext,
                    release = release,
                    onProgress = { percent ->
                        mainHandler.post {
                            if (updateInstallState is UpdateInstallState.Downloading) {
                                updateInstallState = UpdateInstallState.Downloading(percent)
                            }
                        }
                    },
                    onVerifying = {
                        mainHandler.post { updateInstallState = UpdateInstallState.Verifying }
                    }
                )
                mainHandler.post {
                    when (result) {
                        is UpdateInstallResult.Verified -> {
                            verifiedUpdateFile = result.file
                            launchUpdateInstaller(result.file)
                        }
                        is UpdateInstallResult.Failed ->
                            updateInstallState = UpdateInstallState.Failed(result.reason)
                    }
                }
            } catch (cancelled: CancellationException) {
                mainHandler.post {
                    if (updateInstallState is UpdateInstallState.Downloading ||
                        updateInstallState == UpdateInstallState.Verifying
                    ) {
                        updateInstallState = UpdateInstallState.Idle
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                mainHandler.post {
                    updateInstallState = UpdateInstallState.Failed(UpdateInstallFailure.UNKNOWN)
                }
            }
        }
    }

    fun cancelUpdateDownload() {
        updateJob?.cancel()
        updateJob = null
        verifiedUpdateFile = null
        UpdateInstallStatus.clear()
        updateInstallState = UpdateInstallState.Idle
        updateInstallerScope.launch { UpdateInstaller.clearCache(appContext) }
    }

    fun dismissUpdateDialog() {
        if (updateJob?.isActive == true) return
        // Do not delete a verified APK while the system installer may still be reading it. The next
        // download deletes any leftover file before it starts.
        val clearCache = updateInstallState !is UpdateInstallState.Ready
        pendingUpdate = null
        verifiedUpdateFile = null
        UpdateInstallStatus.clear()
        updateInstallState = UpdateInstallState.Idle
        if (clearCache) {
            updateInstallerScope.launch { UpdateInstaller.clearCache(appContext) }
        }
    }

    fun syncEnvironment() {
        val granted = VpnAuthorization.isGranted(appContext)
        vpnAuthorized = granted
        settingsStore.setVpnAuthorizationGranted(granted)
        DnsStatsRepository.refresh()
        // Re-read on every resume: the strength section must never describe a Private DNS mode the
        // user has already changed in Android's settings and come back from.
        privateDnsMode = PrivateDnsSettings.read(appContext).mode
        dnsFallbackEnabled = settingsStore.load().dnsAutoFallbackEnabled
        // Re-read on every resume: the exemption may have been granted in the system dialog or
        // revoked in the system settings, and the home card must describe the answer as it is now.
        batteryExemptionGranted = BatteryOptimization.isExempt(appContext)
        // Read-only probe for the guide's plain notice; it never stops or reconfigures that VPN.
        anotherVpnActive = OtherVpnProbe.anotherVpnHoldsTheSlot(appContext, KuiesVpnService.isRunning)
        // Re-read on every resume: the user may have turned Android's Always-on VPN on or off in the
        // system settings and come back, and this section must not describe the old answer.
        alwaysOnVpn = AlwaysOnVpnProbe.isEnabledFor(
            appContext.packageName,
            AlwaysOnVpnProbe.readConfiguredApp(appContext)
        )
        when {
            !granted -> ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRevoked())
            ProtectionStatusHolder.state == ProtectionState.NOT_AUTHORIZED ->
                ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRequest(true))
        }
        // Returning from the "install unknown apps" page: continue with the same verified APK, but
        // only when the user actually granted the permission. The file is never re-downloaded here.
        if (updateInstallState == UpdateInstallState.NeedsInstallPermission) {
            val file = verifiedUpdateFile
            if (file != null && file.isFile && appContext.packageManager.canRequestPackageInstalls()) {
                updateInstallState = UpdateInstallState.Ready
                launchUpdateInstaller(file)
            }
        }
    }

    /**
     * Applies an interception mode.
     *
     * There is no "off" choice: turning protection off is the home switch's job, and picking a mode
     * only chooses which lists are used — it never starts the tunnel.
     *
     * The coordinator calls back twice: immediately with the state that needs no network, then once
     * the download settled. Only the second callback clears the busy flag, so the screen cannot claim
     * the download finished while it is still running.
     */
    fun selectFilterMode(mode: FilterMode) {
        if (filterBusy) return
        filterBusy = true
        FilterCoordinatorHolder.get(appContext).selectMode(
            mode = mode,
            onStatus = { status -> mainHandler.post { filterStatus = status } },
            onFinished = { mainHandler.post { filterBusy = false } }
        )
    }

    /**
     * Turns one custom-mode source on or off.
     *
     * The selection is published before any network work, so ticking a source that is already
     * downloaded takes effect immediately. A source with no stored content is downloaded once.
     */
    fun selectFilterSource(source: FilterSource, selected: Boolean) {
        if (filterBusy) return
        filterBusy = true
        FilterCoordinatorHolder.get(appContext).selectSource(
            source = source,
            selected = selected,
            onStatus = { status -> mainHandler.post { filterStatus = status } },
            onFinished = { mainHandler.post { filterBusy = false } }
        )
    }

    /**
     * Re-downloads the lists that are already in use, on the user's explicit request.
     *
     * A failure keeps the previous lists and reports itself through the status, and nothing here can
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
     * Turns automatic updates on or off.
     *
     * The preference is stored first and the scheduler is then made to agree with it, so the platform
     * and the screen cannot disagree. Switching it off cancels the periodic job only — 立即更新 keeps
     * working exactly as before, because it never touches the scheduler.
     */
    fun setAutoUpdateEnabled(enabled: Boolean) {
        val updated = updateStore.load().copy(enabled = enabled)
        updateStore.save(updated)
        autoUpdate = updated
        FilterUpdateSchedulePolicy.apply(updated, updateScheduler)
    }

    /** Changes the interval. The unique job is updated in place, so no second job is ever created. */
    fun setUpdateInterval(interval: UpdateInterval) {
        val updated = updateStore.load().copy(interval = interval)
        updateStore.save(updated)
        autoUpdate = updated
        FilterUpdateSchedulePolicy.apply(updated, updateScheduler)
    }

    fun setBootAutostart(enabled: Boolean) {
        protectionIntentStore.setBootAutostartEnabled(enabled)
        bootAutostart = enabled
    }

    /**
     * Stores the DNS backup choice and asks a running tunnel to apply it.
     *
     * The switch is persisted first, so a stopped tunnel reads the same answer when it next starts.
     * A running tunnel verifies the primary before leaving the backup; it never keeps using a
     * backup the user just switched off.
     */
    fun setDnsFallbackEnabled(enabled: Boolean) {
        settingsStore.setDnsAutoFallbackEnabled(enabled)
        dnsFallbackEnabled = enabled
        if (KuiesVpnService.isRunning) {
            KuiesVpnService.notifyDnsFallbackSettingChanged(appContext)
        }
    }

    /**
     * Opens Android's battery-exemption screen for this app, on the user's tap only.
     *
     * Nothing is granted here and nothing is stored: the platform owns the dialog or the settings
     * page, and the real answer is read again when the app resumes. When no system screen can be
     * opened the user gets a short notice instead of a crash, and protection keeps working exactly
     * as it did before — the exemption is never a condition for it.
     */
    fun requestBatteryExemption() {
        if (BatteryOptimization.isExempt(appContext)) {
            batteryExemptionGranted = true
            return
        }
        if (!BatteryOptimization.openRequest(appContext)) {
            Toast.makeText(appContext, R.string.home_background_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Toggles one app's protection.
     *
     * The desired set is persisted before any VPN work, so a failed rebuild or a stopped tunnel never
     * loses the user's choice. A running tunnel is rebuilt through the service's safe teardown/start
     * path; a stopped tunnel only records the setting for the next start.
     */
    fun setAppProtected(packageName: String, protected: Boolean) {
        if (packageName == appContext.packageName) return
        val updated = if (protected) {
            excludedPackages - packageName
        } else {
            excludedPackages + packageName
        }
        if (updated == excludedPackages) return
        appExclusionStore.save(updated)
        excludedPackages = updated
        appApplyResult = null
        if (KuiesVpnService.isRunning) {
            appProtectionStatus = AppProtectionStatus.APPLYING
            KuiesVpnService.rebuildExclusions(appContext)
        } else {
            appProtectionStatus = AppProtectionStatus.SAVED
        }
    }

    /** Clears every exclusion; the caller has already shown a confirmation dialog. */
    fun restoreAllAppProtection() {
        if (excludedPackages.isEmpty()) return
        appExclusionStore.clear()
        excludedPackages = emptySet()
        appApplyResult = null
        if (KuiesVpnService.isRunning) {
            appProtectionStatus = AppProtectionStatus.APPLYING
            KuiesVpnService.rebuildExclusions(appContext)
        } else {
            appProtectionStatus = AppProtectionStatus.SAVED
        }
    }

    /**
     * Opens Android's own VPN screen so the user can set Always-on VPN themselves.
     *
     * Nothing is changed from here: the app explains and offers the door, and the system setting stays
     * the user's decision. That is also why this is only ever reached by a tap.
     */
    fun openVpnSettings() {
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Asks GitHub for the latest stable release, once.
     *
     * Off the main thread, and behind both this flag and the checker's own guard, so a second tap while
     * the first is still running neither blocks the screen nor spends another of the rate-limited
     * requests. The progress flag is cleared in every case — including a failure — so a manual retry is
     * always possible, and nothing is scheduled: this path is only ever entered by a tap.
     */
    fun checkAppUpdate() {
        if (updateChecking) return
        updateChecking = true
        executor.execute {
            val outcome = runCatching { updateChecker.check() }
                .getOrElse { UpdateCheckOutcome.Unavailable(UpdateCheckFailure.UNREADABLE) }
            mainHandler.post {
                updateChecking = false
                when (outcome) {
                    // Another check still owns the state, so it stays exactly as it was.
                    UpdateCheckOutcome.Busy -> Unit
                    is UpdateCheckOutcome.UpdateAvailable -> {
                        updateOutcome = outcome
                        updateInstallState = UpdateInstallState.Idle
                        verifiedUpdateFile = null
                        pendingUpdate = outcome.release
                    }
                    else -> updateOutcome = outcome
                }
            }
        }
    }

    fun onToggleProtection() {
        when (ProtectionStatusHolder.state) {
            ProtectionState.ACTIVE, ProtectionState.STARTING -> {
                protectionIntentStore.setProtectionDesiredOn(false)
                KuiesVpnService.stop(appContext)
                ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStopped(), null)
            }
            ProtectionState.ERROR -> {
                if (KuiesVpnService.isRunning) {
                    protectionIntentStore.setProtectionDesiredOn(false)
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
            dnsFallbackEnabled = dnsFallbackEnabled,
            dnsFallbackStatus = ProtectionStatusHolder.dnsFallbackStatus,
            batteryExemptionGranted = batteryExemptionGranted,
            onSetDnsFallbackEnabled = { enabled -> setDnsFallbackEnabled(enabled) },
            onRequestBatteryExemption = { requestBatteryExemption() },
            onToggleProtection = { onToggleProtection() },
            onOpenAppProtection = {
                appProtectionReturnTo = BlockDestination.HOME
                if (appProtectionStatus != AppProtectionStatus.APPLYING) {
                    appProtectionStatus = AppProtectionStatus.IDLE
                }
                destination = BlockDestination.APP_PROTECTION
            },
            onOpenSettings = { destination = BlockDestination.SETTINGS },
            modifier = modifier
        )

        BlockDestination.SETTINGS -> SettingsScreen(
            appVersion = appVersion,
            themeMode = themeMode,
            onSetThemeMode = onThemeModeChange,
            filterStatus = filterStatus,
            filterBusy = filterBusy,
            filterUpdating = filterUpdating,
            privateDnsMode = privateDnsMode,
            // The real state, not a yes/no: the strength section tells a fresh install, a start in
            // progress, a working tunnel and a failure apart — see FilterPresentation.stateLabel.
            protectionState = protectionState,
            isServiceRunning = KuiesVpnService.isRunning,
            onSelectMode = { mode -> selectFilterMode(mode) },
            onUpdateFilterNow = { updateFilterNow() },
            onSetSourceSelected = { source, selected -> selectFilterSource(source, selected) },
            autoUpdate = autoUpdate,
            onSetAutoUpdateEnabled = { enabled -> setAutoUpdateEnabled(enabled) },
            onSetUpdateInterval = { interval -> setUpdateInterval(interval) },
            bootAutostartEnabled = bootAutostart,
            alwaysOnVpnEnabled = alwaysOnVpn,
            anotherVpnActive = anotherVpnActive,
            onSetBootAutostart = { enabled -> setBootAutostart(enabled) },
            onOpenVpnSettings = { openVpnSettings() },
            onReplayGuide = {
                // A replay: the two pages are shown over settings, and leaving them records nothing
                // and changes no stored value, so the guide can never come back by itself from here.
                guidePage = OnboardingPage.VPN_PERMISSION
                guideReplay = true
                guideVisible = true
            },
            appUpdate = updateOutcome,
            appUpdateChecking = updateChecking,
            pendingUpdate = pendingUpdate,
            updateInstallState = updateInstallState,
            onCheckAppUpdate = { checkAppUpdate() },
            onDownloadUpdate = { pendingUpdate?.let { startUpdateDownload(it) } },
            onCancelUpdateDownload = { cancelUpdateDownload() },
            onInstallVerifiedUpdate = { verifiedUpdateFile?.let { launchUpdateInstaller(it) } },
            onOpenInstallPermission = { openInstallPermissionSettings() },
            onDismissUpdateDialog = { dismissUpdateDialog() },
            onBack = { destination = BlockDestination.HOME },
            modifier = modifier
        )

        BlockDestination.APP_PROTECTION -> AppProtectionScreen(
            apps = installedApps,
            loading = appsLoading,
            loadFailed = appsLoadFailed,
            excludedPackages = excludedPackages,
            status = appProtectionStatus,
            protectionRunning = KuiesVpnService.isRunning,
            onSetProtected = { packageName, protected ->
                setAppProtected(packageName, protected)
            },
            onRestoreAll = { restoreAllAppProtection() },
            onBack = { destination = BlockDestination.SETTINGS },
            modifier = modifier
        )
    }
}
