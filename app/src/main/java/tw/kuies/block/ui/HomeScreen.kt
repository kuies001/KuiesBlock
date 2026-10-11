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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.kuies.block.R
import tw.kuies.block.core.DnsPath
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.data.BlockStats
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsConnectionScenario
import tw.kuies.block.dns.PrivateDnsState

@Composable
fun HomeScreen(
    protectionState: ProtectionState,
    protectionError: ProtectionError?,
    blockStats: BlockStats,
    dnsScenario: DnsConnectionScenario,
    dnsStatus: DnsCheckStatus,
    dnsHost: String,
    dnsChecking: Boolean,
    privateDnsState: PrivateDnsState = PrivateDnsState.UNKNOWN,
    isServiceRunning: Boolean = false,
    dnsPath: DnsPath = DnsPath.INTERCEPTED,
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
            isServiceRunning = isServiceRunning,
            dnsPath = dnsPath,
            onToggleProtection = onToggleProtection
        )

        if (privateDnsState.isStrictHostname) {
            PrivateDnsNoticeCard(
                target = ProtectionPresentation.privateDnsTarget(privateDnsState, dnsHost)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            QueriesCard(stats = blockStats, modifier = Modifier.weight(1f))
            BlockedCard(stats = blockStats, modifier = Modifier.weight(1f))
        }

        DnsStatusCard(
            scenario = dnsScenario,
            status = dnsStatus,
            host = dnsHost,
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
    isServiceRunning: Boolean,
    dnsPath: DnsPath,
    onToggleProtection: () -> Unit
) {
    val containerColor = when (state) {
        ProtectionState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        ProtectionState.STARTING -> MaterialTheme.colorScheme.secondaryContainer
        ProtectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
        ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE ->
            MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (state) {
        ProtectionState.ACTIVE -> MaterialTheme.colorScheme.primary
        ProtectionState.STARTING -> MaterialTheme.colorScheme.secondary
        ProtectionState.ERROR -> MaterialTheme.colorScheme.error
        ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconRes = when (state) {
        ProtectionState.ACTIVE -> R.drawable.ic_shield
        ProtectionState.STARTING -> R.drawable.ic_shield
        ProtectionState.ERROR -> R.drawable.ic_shield_alert
        ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE ->
            R.drawable.ic_shield_off
    }

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
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                if (state == ProtectionState.STARTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(108.dp),
                        color = contentColor,
                        strokeWidth = 3.dp
                    )
                }
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(68.dp)
                )
            }
            Text(
                text = ProtectionPresentation.title(state),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = ProtectionPresentation.description(state, error, dnsPath),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            InterceptionPill(state = state, dnsPath = dnsPath)
            val actionColorRole = ProtectionPresentation.actionColorRole(
                ProtectionPresentation.actionTone(state, isServiceRunning)
            )
            Button(
                onClick = onToggleProtection,
                modifier = Modifier.fillMaxWidth(),
                colors = when (actionColorRole) {
                    ProtectionActionColorRole.PRIMARY -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                    ProtectionActionColorRole.SECONDARY_CONTAINER -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    ProtectionActionColorRole.ERROR_CONTAINER -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            ) {
                Text(ProtectionPresentation.primaryActionLabel(state, isServiceRunning))
            }
        }
    }
}

@Composable
private fun InterceptionPill(state: ProtectionState, dnsPath: DnsPath) {
    val (container, content) = when (state) {
        ProtectionState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        ProtectionState.STARTING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.secondary
        ProtectionState.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(
                    when {
                        // An active tunnel that is not intercepting must not show a check mark.
                        state == ProtectionState.ACTIVE && dnsPath == DnsPath.SYSTEM_PRIVATE_DNS ->
                            R.drawable.ic_info
                        state == ProtectionState.ACTIVE -> R.drawable.ic_check_circle
                        state == ProtectionState.STARTING -> R.drawable.ic_refresh
                        state == ProtectionState.ERROR -> R.drawable.ic_warning
                        else -> R.drawable.ic_block
                    }
                ),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = ProtectionPresentation.interceptionLabel(state, dnsPath),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PrivateDnsNoticeCard(target: PrivateDnsTarget) {
    val titleRes = when (target) {
        PrivateDnsTarget.OUR_HOSTNAME -> R.string.home_private_dns_target_ours
        PrivateDnsTarget.OTHER_HOSTNAME -> R.string.home_private_dns_target_other
        PrivateDnsTarget.UNKNOWN_HOSTNAME -> R.string.home_private_dns_target_unknown
        PrivateDnsTarget.NOT_STRICT -> return
    }
    val detailRes = when (target) {
        PrivateDnsTarget.OUR_HOSTNAME -> R.string.home_private_dns_detail_ours
        PrivateDnsTarget.OTHER_HOSTNAME -> R.string.home_private_dns_detail_other
        PrivateDnsTarget.UNKNOWN_HOSTNAME -> R.string.home_private_dns_detail_unknown
        PrivateDnsTarget.NOT_STRICT -> return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(detailRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    // Never claims the system's private DNS validated: the app cannot read that.
                    text = stringResource(R.string.home_private_dns_validation_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
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

/**
 * The DNS card keeps to a headline and at most one short line. The fuller explanation lives only on
 * the settings screen, so the same wording is never duplicated across both.
 */
@Composable
private fun DnsStatusCard(
    scenario: DnsConnectionScenario,
    status: DnsCheckStatus,
    host: String,
    checking: Boolean,
    onRefresh: () -> Unit
) {
    val colors = dnsStatusColors(scenario)
    val headline = if (scenario == DnsConnectionScenario.UNKNOWN) {
        DnsPresentation.statusLabel(status)
    } else {
        DnsPresentation.scenarioLabel(scenario)
    }
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
                painter = painterResource(dnsStatusIcon(scenario)),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.accent
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = host,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DnsPresentation.scenarioShortHint(scenario)?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = colors.accent
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
