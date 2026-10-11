package tw.kuies.block.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.kuies.block.R
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.data.BlockStats
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.dns.PrivateDnsSettings

@Composable
fun HomeScreen(
    protectionState: ProtectionState,
    protectionError: ProtectionError?,
    blockStats: BlockStats,
    dnsStatus: DnsCheckStatus,
    dnsHost: String,
    privateDnsMode: PrivateDnsMode,
    dnsChecking: Boolean,
    onToggleProtection: () -> Unit,
    onRunDnsDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
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
        HomeHeader(onOpenSettings = onOpenSettings)

        ProtectionCard(
            state = protectionState,
            error = protectionError,
            onToggleProtection = onToggleProtection
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            QueriesCard(stats = blockStats, modifier = Modifier.weight(1f))
            BlockedCard(stats = blockStats, modifier = Modifier.weight(1f))
        }

        DnsStatusCard(
            status = dnsStatus,
            host = dnsHost,
            privateDnsMode = privateDnsMode,
            checking = dnsChecking,
            onRefresh = onRunDnsDiagnostics
        )

        Text(
            text = stringResource(R.string.home_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.action_open_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProtectionCard(
    state: ProtectionState,
    error: ProtectionError?,
    onToggleProtection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(
                        if (ProtectionStateRules.isInterceptionActive(state)) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield),
                    contentDescription = null,
                    tint = if (ProtectionStateRules.isInterceptionActive(state)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.size(72.dp)
                )
            }
            Text(
                text = ProtectionPresentation.title(state),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = ProtectionPresentation.description(state, error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            InterceptionPill(state = state)
            Button(onClick = onToggleProtection, modifier = Modifier.fillMaxWidth()) {
                Text(ProtectionPresentation.primaryActionLabel(state))
            }
        }
    }
}

@Composable
private fun InterceptionPill(state: ProtectionState) {
    val active = ProtectionStateRules.isInterceptionActive(state)
    val container =
        if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val content =
        if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_block),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = ProtectionPresentation.interceptionLabel(state),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun QueriesCard(stats: BlockStats, modifier: Modifier = Modifier) {
    InfoCard(
        modifier = modifier,
        iconRes = R.drawable.ic_dns,
        label = stringResource(R.string.home_queries_today)
    ) {
        Text(
            text = stats.queriesToday.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.home_queries_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BlockedCard(stats: BlockStats, modifier: Modifier = Modifier) {
    InfoCard(
        modifier = modifier,
        iconRes = R.drawable.ic_block,
        label = stringResource(R.string.home_blocked_today)
    ) {
        Text(
            text = stats.blockedToday?.toString() ?: stringResource(R.string.value_no_data),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (stats.hasBlockedData) {
                stringResource(R.string.home_queries_caption)
            } else {
                stringResource(R.string.home_blocked_no_data)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DnsStatusCard(
    status: DnsCheckStatus,
    host: String,
    privateDnsMode: PrivateDnsMode,
    checking: Boolean,
    onRefresh: () -> Unit
) {
    val shown = if (checking) DnsCheckStatus.CHECKING else status
    InfoCard(
        modifier = Modifier.fillMaxWidth(),
        iconRes = R.drawable.ic_dns,
        label = stringResource(R.string.home_dns_status),
        action = {
            IconButton(onClick = onRefresh, enabled = !checking) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.action_recheck_dns),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(statusIcon(shown)),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = statusColor(shown)
            )
            Text(
                text = DnsPresentation.statusLabel(shown),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (PrivateDnsSettings.mayBypassInterception(privateDnsMode)) {
            Text(
                text = stringResource(R.string.home_private_dns_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    iconRes: Int,
    label: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                action?.invoke()
            }
            content()
        }
    }
}

private fun statusIcon(status: DnsCheckStatus): Int = when (status) {
    DnsCheckStatus.OK -> R.drawable.ic_check_circle
    DnsCheckStatus.WARNING -> R.drawable.ic_warning
    DnsCheckStatus.ERROR -> R.drawable.ic_warning
    DnsCheckStatus.CHECKING -> R.drawable.ic_refresh
    DnsCheckStatus.NOT_CHECKED -> R.drawable.ic_info
}

@Composable
private fun statusColor(status: DnsCheckStatus): Color = when (status) {
    DnsCheckStatus.OK -> MaterialTheme.colorScheme.primary
    DnsCheckStatus.WARNING -> MaterialTheme.colorScheme.secondary
    DnsCheckStatus.ERROR -> MaterialTheme.colorScheme.error
    DnsCheckStatus.CHECKING, DnsCheckStatus.NOT_CHECKED -> MaterialTheme.colorScheme.onSurfaceVariant
}
