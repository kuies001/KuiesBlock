package tw.kuies.block.ui

import androidx.compose.foundation.background
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
        DiagnosticsSection(
            dnsReport = dnsReport,
            dnsChecking = dnsChecking,
            onRunDnsDiagnostics = onRunDnsDiagnostics
        )
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
