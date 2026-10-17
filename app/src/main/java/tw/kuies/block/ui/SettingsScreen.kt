package tw.kuies.block.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tw.kuies.block.R
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsDiagnosticReport
import tw.kuies.block.dns.DnsResolutionState
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.filter.FilterAutoUpdateSettings
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.FilterUpdateRecord
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.UpdateInterval
import tw.kuies.block.update.ReleaseInfo
import tw.kuies.block.update.UpdateCheckOutcome
import tw.kuies.block.update.UpdatePresentation

/**
 * Settings, kept to what a user genuinely needs: the privacy statement, the one diagnostic that
 * helps when protection will not start, and the app version.
 *
 * There is deliberately no transport picker: KuiesBlock implements a local DoH relay only, and
 * under strict Private DNS the DoT belongs to Android, so choosing a transport here could not do
 * anything except mislead. Anything that is not a real, working function is left out.
 */
@Composable
fun SettingsScreen(
    dnsReport: DnsDiagnosticReport,
    dnsChecking: Boolean,
    appVersion: String,
    filterStatus: FilterStatus?,
    filterBusy: Boolean,
    filterUpdating: Boolean,
    subscriptionBusyId: String?,
    privateDnsMode: PrivateDnsMode,
    /** The real protection state; the strength section derives its status line from it. */
    protectionState: ProtectionState,
    /** Passed through so a failure says which failure it was, exactly as the home screen does. */
    isServiceRunning: Boolean,
    onSelectStrength: (FilterProfile) -> Unit,
    onUpdateFilterNow: () -> Unit,
    /** [isAllow] picks the list the rule belongs to; the two lists are edited by the same pair. */
    onAddManualRule: (ManualRule, Boolean) -> Unit,
    onRemoveManualRule: (ManualRule, Boolean) -> Unit,
    onAddSubscription: (FilterSubscription) -> Unit,
    onSetSubscriptionEnabled: (FilterSubscription, Boolean) -> Unit,
    onUpdateSubscription: (FilterSubscription) -> Unit,
    onDeleteSubscription: (FilterSubscription) -> Unit,
    /** The automatic-update preference and the outcome of its last run, or null until they are read. */
    autoUpdate: FilterAutoUpdateSettings?,
    updateRecord: FilterUpdateRecord?,
    onSetAutoUpdateEnabled: (Boolean) -> Unit,
    onSetUpdateInterval: (UpdateInterval) -> Unit,
    /** Whether protection should come back by itself after a restart, and how the platform can help. */
    bootAutostartEnabled: Boolean,
    alwaysOnVpnEnabled: Boolean,
    anotherVpnActive: Boolean,
    onSetBootAutostart: (Boolean) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onRunDnsDiagnostics: () -> Unit,
    /** Shows the two-page first-run guide again. Records nothing and changes no setting. */
    onReplayGuide: () -> Unit,
    /** The app-update check: what the last one found, or null before the first one. */
    appUpdate: UpdateCheckOutcome?,
    /** Whether a lookup is running now, which the button shows and disables itself for. */
    appUpdateChecking: Boolean,
    /** The release to offer in the update dialog, or null when there is nothing to show. */
    pendingUpdate: ReleaseInfo?,
    onCheckAppUpdate: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    /** Opens [String], which the caller validates again before it ever reaches the browser. */
    onOpenReleasePage: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsHeader(onBack = onBack)
        InterceptionStrengthSection(
            status = filterStatus,
            busy = filterBusy,
            privateDnsMode = privateDnsMode,
            protectionState = protectionState,
            isServiceRunning = isServiceRunning,
            onSelectStrength = onSelectStrength
        )
        FilterSourceSection(
            status = filterStatus,
            updating = filterUpdating,
            autoUpdate = autoUpdate,
            updateRecord = updateRecord,
            onUpdateNow = onUpdateFilterNow,
            onSetAutoUpdateEnabled = onSetAutoUpdateEnabled,
            onSetUpdateInterval = onSetUpdateInterval
        )
        // The user's own rules and sources, rendered only once the status has been read: both sections
        // act on the objects it carries rather than on a guess about what might be configured.
        filterStatus?.let { status ->
            AllowBlockSection(
                allow = status.manualAllow,
                block = status.manualBlock,
                onAddAllow = { onAddManualRule(it, true) },
                onAddBlock = { onAddManualRule(it, false) },
                onRemoveAllow = { onRemoveManualRule(it, true) },
                onRemoveBlock = { onRemoveManualRule(it, false) },
                modifier = Modifier.fillMaxWidth()
            )
            SubscriptionSection(
                subscriptions = status.customSources,
                busyId = subscriptionBusyId,
                onAdd = onAddSubscription,
                onSetEnabled = onSetSubscriptionEnabled,
                onUpdate = onUpdateSubscription,
                onDelete = onDeleteSubscription,
                modifier = Modifier.fillMaxWidth()
            )
        }
        BootAutostartSection(
            enabled = bootAutostartEnabled,
            alwaysOnVpnEnabled = alwaysOnVpnEnabled,
            anotherVpnActive = anotherVpnActive,
            onSetEnabled = onSetBootAutostart,
            onOpenVpnSettings = onOpenVpnSettings
        )
        DiagnosticsSection(
            dnsReport = dnsReport,
            dnsChecking = dnsChecking,
            onRunDnsDiagnostics = onRunDnsDiagnostics
        )
        GuideSection(onReplayGuide = onReplayGuide)
        PrivacySection()
        AboutSection(appVersion = appVersion)
        // Deliberately the last item on the page: checking for a new version is the least frequent
        // thing a user needs, and it must not push the protections and the lists above it out of reach.
        AppUpdateSection(
            currentVersion = appVersion,
            checking = appUpdateChecking,
            outcome = appUpdate,
            onCheck = onCheckAppUpdate
        )
    }
    pendingUpdate?.let { release ->
        UpdateAvailableDialog(
            release = release,
            currentVersion = appVersion,
            onDismiss = onDismissUpdateDialog,
            onOpenRelease = onOpenReleasePage
        )
    }
}

/**
 * The way back to the first-run guide.
 *
 * The guide is shown by itself exactly once, so this is how a user finds it again — and it is only
 * that: the button replays the two pages and writes nothing, so it cannot clear the authorization,
 * the strength, the downloaded lists or any rule.
 */
@Composable
private fun GuideSection(onReplayGuide: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_guide_section)) {
        Text(
            text = stringResource(R.string.settings_guide_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onReplayGuide,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_replay_guide))
        }
    }
}

/**
 * The filter source the engine is actually using, and the one action that changes it.
 *
 * Every value comes from [FilterPresentation], so this screen cannot show a rule count that is not in
 * effect, nor call a failed update a success. There is deliberately no custom-subscription editor
 * yet: only the three built-in tiers are managed here, and a half-finished editor would promise
 * management the app cannot honour.
 */
@Composable
private fun FilterSourceSection(
    status: FilterStatus?,
    updating: Boolean,
    autoUpdate: FilterAutoUpdateSettings?,
    updateRecord: FilterUpdateRecord?,
    onUpdateNow: () -> Unit,
    onSetAutoUpdateEnabled: (Boolean) -> Unit,
    onSetUpdateInterval: (UpdateInterval) -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_filter_sources_section)) {
        if (status == null) {
            Text(
                text = stringResource(R.string.filter_state_label_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        Text(
            text = FilterPresentation.mainListName(status),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "維護者：${FilterPresentation.MAINTAINER}・${FilterPresentation.MAINTAINER_HOME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = FilterPresentation.LICENCE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LabeledValue(
            label = stringResource(R.string.filter_source_enabled),
            value = FilterPresentation.mainListEnabledLabel(status)
        )
        LabeledValue(
            label = stringResource(R.string.filter_source_rules),
            value = FilterPresentation.effectiveRulesLabel(status)
        )
        LabeledValue(
            label = stringResource(R.string.filter_source_last_update),
            value = FilterPresentation.updateResultLabel(status, System.currentTimeMillis())
        )
        Button(
            onClick = onUpdateNow,
            enabled = !updating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    if (updating) R.string.filter_source_updating else R.string.filter_source_update_now
                )
            )
        }
        AutoUpdateControls(
            settings = autoUpdate,
            record = updateRecord,
            onSetEnabled = onSetAutoUpdateEnabled,
            onSetInterval = onSetUpdateInterval
        )
    }
}

/**
 * The interception strength, plus the state that decides whether it is really doing anything.
 *
 * Only the three tiers are offered: the 關閉 choice was removed, so the local filter is always
 * configured and the home screen's protection switch is the single on/off. The state line is derived
 * by [FilterPresentation] from the real [ProtectionState], which cannot say 「生效中」 while protection
 * is not running, while it is starting, or while the system Private DNS owns name resolution.
 */
@Composable
private fun InterceptionStrengthSection(
    status: FilterStatus?,
    busy: Boolean,
    privateDnsMode: PrivateDnsMode,
    protectionState: ProtectionState,
    isServiceRunning: Boolean,
    onSelectStrength: (FilterProfile) -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_filter_section)) {
        Text(
            text = stringResource(R.string.settings_filter_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FilterProfile.entries.forEach { profile ->
            StrengthOption(
                profile = profile,
                // Until the settings have loaded, nothing is marked as the current choice.
                selected = status != null && status.profile == profile,
                enabled = !busy,
                onSelectStrength = onSelectStrength
            )
        }
        LabeledValue(
            label = stringResource(R.string.filter_state_label),
            value = status?.let {
                FilterPresentation.stateLabel(it, privateDnsMode, protectionState, isServiceRunning, busy)
            } ?: stringResource(R.string.filter_state_label_loading)
        )
    }
}

/** One row of the strength list; a radio row rather than a chip, which cannot overflow at 320dp. */
@Composable
private fun StrengthOption(
    profile: FilterProfile,
    selected: Boolean,
    enabled: Boolean,
    onSelectStrength: (FilterProfile) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelectStrength(profile) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { onSelectStrength(profile) },
            enabled = enabled
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = FilterPresentation.strengthLabel(profile),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = FilterPresentation.strengthDetail(profile),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DiagnosticsSection(
    dnsReport: DnsDiagnosticReport,
    dnsChecking: Boolean,
    onRunDnsDiagnostics: () -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_diagnostics_section)) {
        Button(
            onClick = onRunDnsDiagnostics,
            enabled = !dnsChecking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_run_dns_diagnostics))
        }
        DiagnosticResult(dnsReport = dnsReport, dnsChecking = dnsChecking)
    }
}

/**
 * Shows only categories and outcomes — never a resolved address, a certificate detail, or a raw
 * exception — so the user's own DNS server cannot leak through this screen.
 */
@Composable
private fun DiagnosticResult(dnsReport: DnsDiagnosticReport, dnsChecking: Boolean) {
    val status = if (dnsChecking) DnsCheckStatus.CHECKING else dnsReport.status
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledValue(
            label = stringResource(R.string.settings_diagnostic_result),
            value = DnsPresentation.statusLabel(status)
        )
        if (!dnsChecking && dnsReport.resolution == DnsResolutionState.FAILED) {
            Text(
                text = DnsPresentation.resolutionFailureLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        dnsReport.probes.forEach { probe ->
            Text(
                text = stringResource(
                    R.string.settings_diagnostic_probe,
                    probe.label,
                    DnsPresentation.probeSummary(probe)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PrivacySection() {
    SectionCard(title = stringResource(R.string.settings_privacy_section)) {
        Text(
            text = stringResource(R.string.settings_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutSection(appVersion: String) {
    SectionCard(title = stringResource(R.string.settings_about_section)) {
        LabeledValue(
            label = stringResource(R.string.settings_about_version),
            value = appVersion
        )
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The one place the app's own version is checked against GitHub.
 *
 * Deliberately manual and last on the page: it is a lookup the user asks for, never a schedule, and it
 * shares nothing with the filter-list worker. The version shown here is the same `versionName` the
 * about section above displays, so the two can never disagree.
 *
 * The button carries the whole progress state: it reads 檢查中… and is disabled while a lookup runs, so
 * a second tap cannot start a parallel request, and it is enabled again in every outcome so a failure
 * can always be retried by hand.
 */
@Composable
private fun AppUpdateSection(
    currentVersion: String,
    checking: Boolean,
    outcome: UpdateCheckOutcome?,
    onCheck: () -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_update_section)) {
        Text(
            text = stringResource(
                R.string.settings_update_current,
                UpdatePresentation.versionLabel(currentVersion)
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        UpdatePresentation.resultLabel(outcome)?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodyMedium,
                color = if (UpdatePresentation.isFailure(outcome)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Button(
            onClick = onCheck,
            enabled = !checking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    if (checking) R.string.settings_update_checking else R.string.settings_update_check
                )
            )
        }
    }
}

/**
 * The offer shown when a newer stable release exists: its version, the installed one, its notes, and
 * the two answers a user can give.
 *
 * 前往下載 is offered only when the release's own address is this project's GitHub page — the address is
 * data from the network, so [UpdatePresentation.downloadableUrl] vets it — and it opens that page in the
 * browser. Nothing is downloaded, installed or reconfigured from here: no APK is fetched, no unknown
 * sources permission is asked for, and the VPN is never touched.
 */
@Composable
private fun UpdateAvailableDialog(
    release: ReleaseInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onOpenRelease: (String) -> Unit
) {
    val downloadUrl = UpdatePresentation.downloadableUrl(release)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.settings_update_dialog_title,
                    UpdatePresentation.versionLabel(release.tagName)
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_update_dialog_current,
                        UpdatePresentation.versionLabel(currentVersion)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.settings_update_dialog_notes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = release.notes.ifBlank {
                        stringResource(R.string.settings_update_dialog_notes_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (downloadUrl != null) {
                Button(onClick = { onOpenRelease(downloadUrl) }) {
                    Text(stringResource(R.string.settings_update_dialog_download))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_update_dialog_later))
            }
        }
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The automatic-update controls, inside 過濾器管理 on purpose: it keeps the lists that section already
 * manages fresh, and 立即更新 stays the explicit action right above it.
 *
 * The detail line states the two things the switch alone cannot show — only lists that have already
 * been downloaded are refreshed (see `FilterCoordinator.refreshAll`), and the platform may delay the
 * run — because both are true of the implementation.
 */
@Composable
private fun AutoUpdateControls(
    settings: FilterAutoUpdateSettings?,
    record: FilterUpdateRecord?,
    onSetEnabled: (Boolean) -> Unit,
    onSetInterval: (UpdateInterval) -> Unit
) {
    ToggleRow(
        label = stringResource(R.string.filter_auto_update_title),
        checked = settings?.enabled == true,
        enabled = settings != null,
        onCheckedChange = onSetEnabled
    )
    Text(
        text = stringResource(R.string.filter_auto_update_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // The interval is only a real choice while automatic updates are on, so it is only offered then.
    if (settings?.enabled == true) {
        UpdateInterval.entries.forEach { interval ->
            ChoiceRow(
                label = stringResource(
                    when (interval) {
                        UpdateInterval.HOURS_8 -> R.string.filter_auto_update_interval_8
                        UpdateInterval.HOURS_24 -> R.string.filter_auto_update_interval_24
                    }
                ),
                selected = settings.interval == interval,
                onSelect = { onSetInterval(interval) }
            )
        }
    }
    LabeledValue(
        label = stringResource(R.string.filter_auto_update_last_check),
        value = record?.let { FilterPresentation.lastCheckLabel(it, System.currentTimeMillis()) }
            ?: stringResource(R.string.filter_state_label_loading)
    )
}

/**
 * Whether protection should come back by itself after a restart.
 *
 * The text is the honest part of this feature: the platform may delay or skip the attempt, and the
 * reliable route is the system's own Always-on VPN, which the button opens. When that setting already
 * points at KuiesBlock the section says the system owns the restore, so the switch below is never
 * credited with something it did not do.
 */
@Composable
private fun BootAutostartSection(
    enabled: Boolean,
    alwaysOnVpnEnabled: Boolean,
    anotherVpnActive: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onOpenVpnSettings: () -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_boot_section)) {
        Text(
            text = stringResource(R.string.settings_boot_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ToggleRow(
            label = stringResource(R.string.settings_boot_toggle),
            checked = enabled,
            enabled = true,
            onCheckedChange = onSetEnabled
        )
        if (alwaysOnVpnEnabled) {
            Text(
                text = stringResource(R.string.settings_boot_always_on_active),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.settings_boot_always_on_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onOpenVpnSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_boot_open_vpn_settings))
            }
        }
        if (anotherVpnActive) {
            Text(
                text = stringResource(R.string.settings_boot_other_vpn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One on/off row: a switch, because it is independent of any choice listed under it. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** One row of a small exclusive choice; the same shape as the strength rows, so 320dp still fits. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
