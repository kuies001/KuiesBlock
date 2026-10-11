package tw.kuies.block.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tw.kuies.block.R
import tw.kuies.block.data.BlockStats
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsConfig
import tw.kuies.block.dns.DnsConnectionScenario
import tw.kuies.block.dns.DnsDiagnosticReport
import tw.kuies.block.dns.DnsProtocol
import tw.kuies.block.dns.DnsResolutionState
import tw.kuies.block.dns.PrivateDnsState
import tw.kuies.block.doh.DohEndpointOutcome
import tw.kuies.block.doh.DohEndpointPolicy

@Composable
fun SettingsScreen(
    dnsConfig: DnsConfig,
    dnsReport: DnsDiagnosticReport,
    dnsChecking: Boolean,
    dnsScenario: DnsConnectionScenario,
    privateDns: PrivateDnsState,
    blockStats: BlockStats,
    appVersion: String,
    onSelectProtocol: (DnsProtocol) -> Unit,
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
        DnsSection(
            dnsConfig = dnsConfig,
            dnsReport = dnsReport,
            dnsChecking = dnsChecking,
            dnsScenario = dnsScenario,
            privateDns = privateDns,
            onSelectProtocol = onSelectProtocol,
            onRunDnsDiagnostics = onRunDnsDiagnostics
        )
        StatsSection(blockStats = blockStats)
        PrivacySection()
        AboutSection(appVersion = appVersion)
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
private fun DnsSection(
    dnsConfig: DnsConfig,
    dnsReport: DnsDiagnosticReport,
    dnsChecking: Boolean,
    dnsScenario: DnsConnectionScenario,
    privateDns: PrivateDnsState,
    onSelectProtocol: (DnsProtocol) -> Unit,
    onRunDnsDiagnostics: () -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_dns_section)) {
        LabeledValue(
            label = stringResource(R.string.settings_dns_host_label),
            value = dnsConfig.host
        )
        LabeledValue(
            label = stringResource(R.string.settings_dns_endpoint_label),
            value = when (val outcome = DohEndpointPolicy.build(dnsConfig)) {
                is DohEndpointOutcome.Ok -> outcome.endpoint.url
                is DohEndpointOutcome.Invalid -> "—"
            }
        )

        if (dnsScenario != DnsConnectionScenario.UNKNOWN) {
            ConnectionBanner(
                scenario = dnsScenario,
                explanation = DnsPresentation.scenarioExplanation(dnsScenario, privateDns, dnsConfig.host)
            )
        }

        Text(
            text = stringResource(R.string.settings_dns_protocol_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DnsProtocol.entries.forEach { protocol ->
            ProtocolRow(
                protocol = protocol,
                selected = protocol == dnsConfig.protocol,
                onSelect = { onSelectProtocol(protocol) }
            )
        }
        Text(
            text = stringResource(R.string.settings_dns_protocol_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val matchLabel = DnsPresentation.privateDnsMatchLabel(privateDns, dnsConfig.host)
        val privateDnsValue = if (matchLabel != null) {
            "${DnsPresentation.privateDnsLabel(privateDns.mode)}（$matchLabel）"
        } else {
            DnsPresentation.privateDnsLabel(privateDns.mode)
        }
        LabeledValue(
            label = stringResource(R.string.settings_private_dns_label),
            value = privateDnsValue
        )
        Text(
            text = stringResource(R.string.settings_scope_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

@Composable
private fun ConnectionBanner(scenario: DnsConnectionScenario, explanation: String?) {
    val colors = dnsStatusColors(scenario)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.container,
        contentColor = colors.content,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(dnsStatusIcon(scenario)),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.accent
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = DnsPresentation.scenarioLabel(scenario),
                    style = MaterialTheme.typography.titleMedium
                )
                if (explanation != null) {
                    Text(text = explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProtocolRow(protocol: DnsProtocol, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = protocol.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = protocol.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
private fun StatsSection(blockStats: BlockStats) {
    SectionCard(title = stringResource(R.string.settings_stats_section)) {
        Text(
            text = stringResource(R.string.settings_stats_queries, blockStats.queriesToday),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.settings_stats_failed, blockStats.failedToday),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.settings_stats_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
