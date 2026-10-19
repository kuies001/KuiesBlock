package tw.kuies.block.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.dns.DnsFallbackStatus

/**
 * The whole home screen: the app name, the shield, the protection status, the one button that
 * turns protection on or off, the per-app entry and a short card of plain-language benefits.
 *
 * Kept deliberately free of network detail. It shows no host name, no endpoint, no transport and
 * no counters — that is internal, and a plain user only needs to know whether protection is on. A
 * failure is the one exception: it adds a short reason and the next step, so an error never leaves
 * the user without an explanation.
 *
 * The screen is also free of side effects: nothing here starts, stops or reconfigures the VPN. The
 * only way protection can change from this screen is a tap on the button, whose handler the caller
 * owns.
 */
@Composable
fun HomeScreen(
    protectionState: ProtectionState,
    protectionError: ProtectionError?,
    isServiceRunning: Boolean = false,
    dnsFallbackEnabled: Boolean,
    dnsFallbackStatus: DnsFallbackStatus,
    onSetDnsFallbackEnabled: (Boolean) -> Unit,
    onToggleProtection: () -> Unit,
    onOpenAppProtection: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        HomeHeader(onOpenSettings = onOpenSettings)

        ProtectionCard(
            state = protectionState,
            error = protectionError,
            isServiceRunning = isServiceRunning,
            onToggleProtection = onToggleProtection
        )

        AppProtectionEntry(onOpen = onOpenAppProtection)

        DnsFallbackCard(
            enabled = dnsFallbackEnabled,
            status = dnsFallbackStatus,
            protectionState = protectionState,
            isServiceRunning = isServiceRunning,
            onSetEnabled = onSetDnsFallbackEnabled
        )

        BenefitsCard()
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

/**
 * The one card on the home screen: the shield, the status, and — only while protection is in the
 * error state — a plain-language reason plus what to do about it.
 */
@Composable
private fun ProtectionCard(
    state: ProtectionState,
    error: ProtectionError?,
    isServiceRunning: Boolean,
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
        ProtectionState.ACTIVE, ProtectionState.STARTING -> R.drawable.ic_shield
        ProtectionState.ERROR -> R.drawable.ic_shield_alert
        ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE -> R.drawable.ic_shield_off
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .clip(CircleShape)
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                if (state == ProtectionState.STARTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(128.dp),
                        color = contentColor,
                        strokeWidth = 3.dp
                    )
                }
                Icon(
                    painter = painterResource(iconRes),
                    // Decorative: the status text below carries the same information.
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(80.dp)
                )
            }
            Text(
                text = ProtectionPresentation.title(state, isServiceRunning),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (state == ProtectionState.ERROR) {
                ErrorExplanation(error = error)
            }
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

/** Why protection stopped, and the one thing the user can do about it. */
@Composable
private fun ErrorExplanation(error: ProtectionError?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = ProtectionPresentation.errorReason(error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = ProtectionPresentation.errorAdvice(error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The way into the per-app page, placed on home because that is where a user looks for it.
 *
 * It is a single tappable row, not a control: the switches live on the page it opens, so nothing here
 * can change an exclusion by accident. The count is not shown — the page states it — which keeps this
 * entry to one line of purpose.
 */
@Composable
private fun AppProtectionEntry(onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_app_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.home_app_settings_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                // Decorative: the whole row is the target and its title names the destination.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * The DNS backup card: one switch, one live status line and one fixed explanation.
 *
 * The status comes from the service's verified upstream, never from the switch alone; the
 * presentation object suppresses any claim while protection is stopped or still starting.
 */
@Composable
private fun DnsFallbackCard(
    enabled: Boolean,
    status: DnsFallbackStatus,
    protectionState: ProtectionState,
    isServiceRunning: Boolean,
    onSetEnabled: (Boolean) -> Unit
) {
    val tone = DnsFallbackPresentation.tone(
        protectionState = protectionState,
        isServiceRunning = isServiceRunning,
        enabled = enabled,
        status = status
    )
    val statusColor = when (tone) {
        DnsFallbackTone.SUCCESS -> MaterialTheme.colorScheme.primary
        DnsFallbackTone.WARNING -> MaterialTheme.colorScheme.secondary
        DnsFallbackTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        DnsFallbackTone.ERROR -> MaterialTheme.colorScheme.error
        DnsFallbackTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.dns_fallback_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = enabled, onCheckedChange = onSetEnabled)
            }
            Text(
                text = DnsFallbackPresentation.statusText(
                    protectionState = protectionState,
                    isServiceRunning = isServiceRunning,
                    enabled = enabled,
                    status = status
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )
            Text(
                text = stringResource(R.string.dns_fallback_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Why it is worth keeping protection on, under the status card.
 *
 * There is no heading: the two rows say what they mean on their own, and a title only repeated them.
 *
 * The copy stays modest on purpose: a DNS-level filter can cut down ad loading, but it cannot catch
 * every ad an app serves itself, so the two lines describe a possible saving instead of promising a
 * guaranteed one or naming an unmeasured percentage. Like the rest of the screen, no host name,
 * endpoint, diagnostic or counter appears here, and nothing in this card can start or stop the VPN.
 */
@Composable
private fun BenefitsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BenefitRow(
                iconRes = R.drawable.ic_block,
                title = stringResource(R.string.home_benefit_blocking_title),
                body = stringResource(R.string.home_benefit_blocking_body)
            )
            BenefitRow(
                iconRes = R.drawable.ic_check_circle,
                title = stringResource(R.string.home_benefit_free_title),
                body = stringResource(R.string.home_benefit_free_body)
            )
        }
    }
}

/**
 * One benefit: a small icon beside its title and a single line of explanation. The text column takes
 * the remaining width, so a long line wraps instead of overflowing, and the icon keeps its size
 * when the system font is enlarged.
 */
@Composable
private fun BenefitRow(iconRes: Int, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(iconRes),
            // Decorative: the title next to it carries the same meaning.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(24.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
