package tw.kuies.block.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import tw.kuies.block.R
import tw.kuies.block.dns.DnsConnectionScenario

/**
 * Colors for a DNS status.
 *
 * Healthy is the brand green, the Private DNS bypass warning is the warm orange secondary, and a
 * real connection failure is the error red — so the two are never confused at a glance.
 */
data class DnsStatusColors(val container: Color, val content: Color, val accent: Color)

@Composable
fun dnsStatusColors(scenario: DnsConnectionScenario): DnsStatusColors = when (scenario) {
    DnsConnectionScenario.CONNECTED -> DnsStatusColors(
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        accent = MaterialTheme.colorScheme.primary
    )
    DnsConnectionScenario.BYPASS_RISK -> DnsStatusColors(
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        accent = MaterialTheme.colorScheme.secondary
    )
    DnsConnectionScenario.FAILED -> DnsStatusColors(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        accent = MaterialTheme.colorScheme.error
    )
    DnsConnectionScenario.UNKNOWN -> DnsStatusColors(
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        accent = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

fun dnsStatusIcon(scenario: DnsConnectionScenario): Int = when (scenario) {
    DnsConnectionScenario.CONNECTED -> R.drawable.ic_check_circle
    DnsConnectionScenario.BYPASS_RISK -> R.drawable.ic_warning
    DnsConnectionScenario.FAILED -> R.drawable.ic_warning
    DnsConnectionScenario.UNKNOWN -> R.drawable.ic_info
}
