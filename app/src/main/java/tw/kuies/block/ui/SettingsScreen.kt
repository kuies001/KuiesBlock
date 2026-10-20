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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tw.kuies.block.R
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.data.ThemeMode
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.filter.FilterAutoUpdateSettings
import tw.kuies.block.filter.FilterIntervalDraft
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterSource
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSyncPhase
import tw.kuies.block.filter.UpdateInterval
import tw.kuies.block.update.ReleaseInfo
import tw.kuies.block.update.UpdateCheckOutcome
import tw.kuies.block.update.UpdateInstallState
import tw.kuies.block.update.UpdatePresentation

/**
 * Settings, kept to what a user genuinely needs: the filter, the per-app and boot choices, the
 * privacy statement and the app version.
 *
 * There is deliberately no transport picker: KuiesBlock implements a local DoH relay only, and
 * under strict Private DNS the DoT belongs to Android, so choosing a transport here could not do
 * anything except mislead. Anything that is not a real, working function is left out.
 */
@Composable
fun SettingsScreen(
    appVersion: String,
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    filterStatus: FilterStatus?,
    /** What a mode or source change is doing now; shown beside the state, never in place of it. */
    filterSyncPhase: FilterSyncPhase,
    filterUpdating: Boolean,
    privateDnsMode: PrivateDnsMode,
    /** The real protection state; the strength section derives its status line from it. */
    protectionState: ProtectionState,
    /** Passed through so a failure says which failure it was, exactly as the home screen does. */
    isServiceRunning: Boolean,
    onSelectMode: (FilterMode) -> Unit,
    onUpdateFilterNow: () -> Unit,
    /** Turns one custom-mode source on or off; the last selected source cannot be removed. */
    onSetSourceSelected: (FilterSource, Boolean) -> Unit,
    /** The automatic-update preference, or null until it is read. */
    autoUpdate: FilterAutoUpdateSettings?,
    onSetAutoUpdateEnabled: (Boolean) -> Unit,
    /** Commits the interval the user confirmed with 「儲存並更新」. */
    onSaveUpdateInterval: (UpdateInterval) -> Unit,
    /** Whether protection should come back by itself after a restart, and how the platform can help. */
    bootAutostartEnabled: Boolean,
    alwaysOnVpnEnabled: Boolean,
    anotherVpnActive: Boolean,
    onSetBootAutostart: (Boolean) -> Unit,
    onOpenVpnSettings: () -> Unit,
    /** Shows the two-page first-run guide again. Records nothing and changes no setting. */
    onReplayGuide: () -> Unit,
    /** The app-update check: what the last one found, or null before the first one. */
    appUpdate: UpdateCheckOutcome?,
    /** Whether a lookup is running now, which the button shows and disables itself for. */
    appUpdateChecking: Boolean,
    /** The release to offer in the update dialog, or null when there is nothing to show. */
    pendingUpdate: ReleaseInfo?,
    /** What the in-app download is doing for [pendingUpdate]. */
    updateInstallState: UpdateInstallState,
    onCheckAppUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onInstallVerifiedUpdate: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
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
        ThemeSection(themeMode = themeMode, onSetThemeMode = onSetThemeMode)
        InterceptionStrengthSection(
            status = filterStatus,
            phase = filterSyncPhase,
            privateDnsMode = privateDnsMode,
            protectionState = protectionState,
            isServiceRunning = isServiceRunning,
            onSelectMode = onSelectMode,
            onSetSourceSelected = onSetSourceSelected
        )
        FilterSourceSection(
            status = filterStatus,
            updating = filterUpdating,
            autoUpdate = autoUpdate,
            onUpdateNow = onUpdateFilterNow,
            onSetAutoUpdateEnabled = onSetAutoUpdateEnabled,
            onSaveUpdateInterval = onSaveUpdateInterval
        )
        BootAutostartSection(
            enabled = bootAutostartEnabled,
            alwaysOnVpnEnabled = alwaysOnVpnEnabled,
            anotherVpnActive = anotherVpnActive,
            onSetEnabled = onSetBootAutostart,
            onOpenVpnSettings = onOpenVpnSettings
        )
        GuideSection(onReplayGuide = onReplayGuide)
        PrivacySection()
        // The version and its manual check share one card: they answer the same question, and two
        // cards repeated the version on the same screen.
        AboutSection(
            appVersion = appVersion,
            checking = appUpdateChecking,
            outcome = appUpdate,
            onCheck = onCheckAppUpdate
        )
    }
    pendingUpdate?.let { release ->
        UpdateAvailableDialog(
            release = release,
            currentVersion = appVersion,
            state = updateInstallState,
            onDownload = onDownloadUpdate,
            onCancelDownload = onCancelUpdateDownload,
            onInstall = onInstallVerifiedUpdate,
            onOpenInstallPermission = onOpenInstallPermission,
            onDismiss = onDismissUpdateDialog
        )
    }
}

/**
 * The theme choice: three radio rows, applied immediately by the Activity-level theme state and
 * persisted in [tw.kuies.block.data.AppSettingsStore]. No preview or extra copy.
 */
@Composable
private fun ThemeSection(themeMode: ThemeMode, onSetThemeMode: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExpandableSectionCard(
        title = stringResource(R.string.settings_theme_section),
        value = stringResource(themeModeLabel(themeMode)),
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        // The requested order is SYSTEM, DARK, LIGHT; the enum order is not the display order.
        listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSetThemeMode(mode)
                        expanded = false
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = themeMode == mode,
                    onClick = {
                        onSetThemeMode(mode)
                        expanded = false
                    }
                )
                Text(
                    text = stringResource(themeModeLabel(mode)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
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
 * effect, nor call a failed update a success. Each active source is named once; the list's maintainer
 * and licence are kept to one compact attribution line instead of a paragraph per source.
 */
@Composable
private fun FilterSourceSection(
    status: FilterStatus?,
    updating: Boolean,
    autoUpdate: FilterAutoUpdateSettings?,
    onUpdateNow: () -> Unit,
    onSetAutoUpdateEnabled: (Boolean) -> Unit,
    onSaveUpdateInterval: (UpdateInterval) -> Unit
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

        val now = System.currentTimeMillis()
        val activeSources = status.sourceStatuses.filter { it.enabled }
        if (activeSources.isEmpty()) {
            Text(
                text = stringResource(R.string.filter_source_none_active),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        activeSources.forEach { source ->
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            FilterPresentation.sourceIssueLabel(source)?.let { issue ->
                Text(
                    text = issue,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (FilterPresentation.sourceIssueIsError(source)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        LabeledValue(
            label = stringResource(R.string.filter_source_rules),
            value = FilterPresentation.effectiveRuleCountLabel(status)
        )
        LabeledValue(
            label = stringResource(R.string.filter_source_last_update),
            value = FilterPresentation.lastUpdateLabel(status, now)
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
            onSetEnabled = onSetAutoUpdateEnabled,
            onSaveInterval = onSaveUpdateInterval
        )
        FilterPresentation.sourceAttributionLabel(status)?.let { attribution ->
            Text(
                text = attribution,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = FilterPresentation.LICENCE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The interception mode, plus the state that decides whether it is really doing anything.
 *
 * Four choices are offered. The three presets are each fixed to one HaGeZi tier; 自訂 is the only mode
 * that shows the source picker. The state line is derived by [FilterPresentation] from the real
 * [ProtectionState], which cannot say 「生效中」 while protection is not running, while it is starting,
 * or while the system Private DNS owns name resolution.
 */
@Composable
private fun InterceptionStrengthSection(
    status: FilterStatus?,
    phase: FilterSyncPhase,
    privateDnsMode: PrivateDnsMode,
    protectionState: ProtectionState,
    isServiceRunning: Boolean,
    onSelectMode: (FilterMode) -> Unit,
    onSetSourceSelected: (FilterSource, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExpandableSectionCard(
        title = stringResource(R.string.settings_filter_section),
        value = status?.let { FilterPresentation.modeSummaryLabel(it.mode) }
            ?: stringResource(R.string.filter_state_label_loading),
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        Text(
            text = stringResource(R.string.settings_filter_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FilterMode.entries.forEach { mode ->
            ModeOption(
                mode = mode,
                // Until the settings have loaded, nothing is marked as the current choice.
                selected = status != null && status.mode == mode,
                // A running update must not lock the choice: switching again is exactly how a slow
                // download is abandoned, and the coordinator merges the requests.
                enabled = true,
                onSelectMode = { selected ->
                    onSelectMode(selected)
                    // Custom keeps the picker open; the three fixed tiers collapse after selection.
                    if (selected != FilterMode.CUSTOM) expanded = false
                }
            )
        }
        if (status?.mode == FilterMode.CUSTOM) {
            CustomSourcePicker(
                status = status,
                onSetSourceSelected = onSetSourceSelected
            )
        }
        LabeledValue(
            label = stringResource(R.string.filter_state_label),
            value = status?.let {
                FilterPresentation.stateLabel(it, privateDnsMode, protectionState, isServiceRunning)
            } ?: stringResource(R.string.filter_state_label_loading)
        )
        val syncHint = status?.let { FilterPresentation.syncHint(it, phase) }
        if (status != null && syncHint != null) {
            Text(
                text = syncHint,
                style = MaterialTheme.typography.bodySmall,
                color = if (FilterPresentation.syncHintIsError(status, phase)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** One row of the mode list; a radio row rather than a chip, which cannot overflow at 320dp. */
@Composable
private fun ModeOption(
    mode: FilterMode,
    selected: Boolean,
    enabled: Boolean,
    onSelectMode: (FilterMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelectMode(mode) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { onSelectMode(mode) },
            enabled = enabled
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = FilterPresentation.modeLabel(mode),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = FilterPresentation.modeDetail(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The source checklist shown only in custom mode.
 *
 * The HaGeZi Multi tiers render as radio rows because exactly one may be active; every other source is
 * a checkbox and may be combined. The last remaining source cannot be unchecked, so custom mode never
 * ends up with nothing to filter with.
 */
@Composable
private fun CustomSourcePicker(
    status: FilterStatus,
    onSetSourceSelected: (FilterSource, Boolean) -> Unit
) {
    Text(
        text = stringResource(R.string.filter_custom_sources_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp)
    )
    Text(
        text = stringResource(R.string.filter_custom_sources_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val selectedCount = status.selectedSources.size
    FilterSource.entries.forEach { source ->
        val selected = source.id in status.selectedSources
        SourceOption(
            source = source,
            selected = selected,
            // A selected checkbox cannot be the last one removed; a radio row changes by selecting
            // another tier rather than by unchecking itself. A running update never locks the list:
            // the debounce merges a row of taps into one download.
            enabled = !selected || source.isMultiTier || selectedCount > 1,
            onSetSelected = { checked -> onSetSourceSelected(source, checked) }
        )
    }
}

@Composable
private fun SourceOption(
    source: FilterSource,
    selected: Boolean,
    enabled: Boolean,
    onSetSelected: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                if (source.isMultiTier) {
                    if (!selected) onSetSelected(true)
                } else {
                    onSetSelected(!selected)
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (source.isMultiTier) {
            RadioButton(
                selected = selected,
                onClick = {
                    if (enabled && !selected) onSetSelected(true)
                },
                enabled = enabled
            )
        } else {
            Checkbox(
                checked = selected,
                onCheckedChange = { checked -> if (enabled) onSetSelected(checked) },
                enabled = enabled
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        ) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = source.purpose,
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
private fun PrivacySection() {
    SectionCard(title = stringResource(R.string.settings_privacy_section)) {
        Text(
            text = stringResource(R.string.settings_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The installed version and the manual GitHub check, in one card.
 *
 * The check is deliberately manual and last on the page: it is a lookup the user asks for, never a
 * schedule, and it shares nothing with the filter-list worker. The version comes from the installed
 * package, so it cannot disagree with what Android reports, and the button carries the whole progress
 * state — 檢查中… and disabled while a lookup runs — so a second tap cannot start a parallel request.
 */
@Composable
private fun AboutSection(
    appVersion: String,
    checking: Boolean,
    outcome: UpdateCheckOutcome?,
    onCheck: () -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_about_section)) {
        Text(
            text = stringResource(
                R.string.settings_update_current,
                UpdatePresentation.versionLabel(appVersion)
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
 * The update offer: version, notes, and the in-app download/verify/install phases.
 *
 * The APK is fetched only after the user taps 下載並更新, and the system installer is opened only after
 * the download passed the checksum, package, version and signing checks. Nothing here stops or
 * rebuilds the VPN, and cancelling a download only removes its own cache file.
 */
@Composable
private fun UpdateAvailableDialog(
    release: ReleaseInfo,
    currentVersion: String,
    state: UpdateInstallState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val busy = state is UpdateInstallState.Downloading ||
        state == UpdateInstallState.Verifying ||
        state == UpdateInstallState.Installing
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
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
                when (state) {
                    UpdateInstallState.Idle -> Unit

                    is UpdateInstallState.Downloading -> {
                        Text(
                            text = stringResource(R.string.settings_update_downloading),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val percent = state.percent
                        if (percent != null) {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(R.string.settings_update_progress, percent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    UpdateInstallState.Verifying -> {
                        Text(
                            text = stringResource(R.string.settings_update_verifying),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    UpdateInstallState.Installing -> {
                        Text(
                            text = stringResource(R.string.settings_update_installing),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    UpdateInstallState.Ready -> Text(
                        text = stringResource(R.string.settings_update_ready),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    UpdateInstallState.NeedsInstallPermission -> Text(
                        text = stringResource(R.string.settings_update_permission_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    is UpdateInstallState.Failed -> Text(
                        text = state.reason.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                UpdateInstallState.Idle -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.settings_update_dialog_download))
                }

                is UpdateInstallState.Downloading -> TextButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.settings_update_cancel))
                }

                UpdateInstallState.Verifying -> Unit
                UpdateInstallState.Installing -> Unit

                UpdateInstallState.Ready -> Button(onClick = onInstall) {
                    Text(stringResource(R.string.settings_update_install_now))
                }

                UpdateInstallState.NeedsInstallPermission -> Button(onClick = onOpenInstallPermission) {
                    Text(stringResource(R.string.settings_update_open_install_settings))
                }

                is UpdateInstallState.Failed -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.settings_update_retry))
                }
            }
        },
        dismissButton = {
            if (!busy) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_update_dialog_later))
                }
            }
        }
    )
}

@Composable
private fun ExpandableSectionCard(
    title: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
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
            ExpandableHeader(
                title = title,
                value = value,
                expanded = expanded,
                onToggle = onToggle
            )
            if (expanded) content()
        }
    }
}

/** The one header shape shared by the three collapsible settings sections. */
@Composable
private fun ExpandableHeader(
    title: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) -90f else 90f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    onSetEnabled: (Boolean) -> Unit,
    onSaveInterval: (UpdateInterval) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = settings?.enabled == true
    val saved = settings?.interval
    // A radio tap only moves this draft. The preference, the periodic job and the lists stay exactly
    // as they are until 「儲存並更新」 is pressed; collapsing the section, switching automatic updates
    // off or leaving the page drops the draft instead of quietly applying it.
    var draft by remember(saved) { mutableStateOf(saved?.let(FilterIntervalDraft::of)) }
    val summary = when {
        settings == null -> stringResource(R.string.filter_state_label_loading)
        enabled -> stringResource(
            R.string.filter_auto_update_on,
            stringResource(autoUpdateIntervalLabel(settings.interval))
        )
        else -> stringResource(R.string.filter_auto_update_off)
    }
    ExpandableHeader(
        title = stringResource(R.string.filter_auto_update_section_title),
        value = summary,
        expanded = expanded,
        onToggle = {
            val next = !expanded
            if (!next && saved != null) draft = FilterIntervalDraft.of(saved)
            expanded = next
        }
    )
    if (expanded) {
        ToggleRow(
            label = stringResource(R.string.filter_auto_update_toggle),
            checked = enabled,
            enabled = settings != null,
            onCheckedChange = { checked ->
                // Turning the feature off keeps the stored interval; an unconfirmed pick is dropped.
                if (!checked && saved != null) draft = FilterIntervalDraft.of(saved)
                onSetEnabled(checked)
            }
        )
        Text(
            text = stringResource(R.string.filter_auto_update_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // The interval is only a real choice while automatic updates are on, so it is only offered then.
        val current = draft
        if (enabled && current != null) {
            UpdateInterval.entries.forEach { interval ->
                ChoiceRow(
                    label = stringResource(autoUpdateIntervalLabel(interval)),
                    selected = current.selected == interval,
                    onSelect = { draft = current.select(interval) }
                )
            }
            if (current.hasPendingChange) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = { onSaveInterval(current.selected) }) {
                        Text(stringResource(R.string.filter_auto_update_save))
                    }
                }
            }
        }
    }
}

private fun autoUpdateIntervalLabel(interval: UpdateInterval): Int = when (interval) {
    UpdateInterval.HOURS_8 -> R.string.filter_auto_update_interval_8
    UpdateInterval.HOURS_24 -> R.string.filter_auto_update_interval_24
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
