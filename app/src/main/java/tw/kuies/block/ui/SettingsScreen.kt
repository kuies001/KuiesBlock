package tw.kuies.block.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tw.kuies.block.R
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsDiagnosticReport
import tw.kuies.block.dns.DnsResolutionState
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.ManualRule

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
    onSelectStrength: (FilterProfile?) -> Unit,
    onUpdateFilterNow: () -> Unit,
    /** [isAllow] picks the list the rule belongs to; the two lists are edited by the same pair. */
    onAddManualRule: (ManualRule, Boolean) -> Unit,
    onRemoveManualRule: (ManualRule, Boolean) -> Unit,
    onAddSubscription: (FilterSubscription) -> Unit,
    onSetSubscriptionEnabled: (FilterSubscription, Boolean) -> Unit,
    onUpdateSubscription: (FilterSubscription) -> Unit,
    onDeleteSubscription: (FilterSubscription) -> Unit,
    onRunDnsDiagnostics: () -> Unit,
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
            onSelectStrength = onSelectStrength
        )
        FilterSourceSection(
            status = filterStatus,
            updating = filterUpdating,
            onUpdateNow = onUpdateFilterNow
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
        DiagnosticsSection(
            dnsReport = dnsReport,
            dnsChecking = dnsChecking,
            onRunDnsDiagnostics = onRunDnsDiagnostics
        )
        PrivacySection()
        AboutSection(appVersion = appVersion)
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
    onUpdateNow: () -> Unit
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
            value = FilterPresentation.sourceEnabledLabel(status.enabled)
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
    }
}

/**
 * The interception strength, plus the state that decides whether it is really doing anything.
 *
 * 「關閉」 is a real option, not an empty one: it is what a fresh install has, and choosing it keeps
 * the downloaded lists on disk. The state line is derived by [FilterPresentation], which cannot say
 * 「生效中」 while the system Private DNS owns name resolution.
 */
@Composable
private fun InterceptionStrengthSection(
    status: FilterStatus?,
    busy: Boolean,
    privateDnsMode: PrivateDnsMode,
    onSelectStrength: (FilterProfile?) -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_filter_section)) {
        Text(
            text = stringResource(R.string.settings_filter_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StrengthOption(
            profile = null,
            selected = status != null && !status.enabled,
            enabled = !busy,
            onSelectStrength = onSelectStrength
        )
        FilterProfile.entries.forEach { profile ->
            StrengthOption(
                profile = profile,
                // Until the settings have loaded, nothing is marked as the current choice.
                selected = status != null && status.enabled && status.profile == profile,
                enabled = !busy,
                onSelectStrength = onSelectStrength
            )
        }
        LabeledValue(
            label = stringResource(R.string.filter_state_label),
            value = status?.let { FilterPresentation.stateLabel(it, privateDnsMode, busy) }
                ?: stringResource(R.string.filter_state_label_loading)
        )
    }
}

/** One row of the strength list; a radio row rather than a chip, which cannot overflow at 320dp. */
@Composable
private fun StrengthOption(
    profile: FilterProfile?,
    selected: Boolean,
    enabled: Boolean,
    onSelectStrength: (FilterProfile?) -> Unit
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
