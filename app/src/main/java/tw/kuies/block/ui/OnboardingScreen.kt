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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.kuies.block.R
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState

/**
 * The two-page first-run guide, as a pure function of its inputs.
 *
 * Nothing here starts, stops or reconfigures anything, and nothing here reads a store: the caller
 * owns every side effect and passes the facts in, so the guide can be rendered for any combination of
 * authorization, other-VPN and protection state without a device or a VPN.
 *
 * The wording is [OnboardingPresentation]'s, which in turn defers the protection claims to
 * [ProtectionPresentation]. The screen therefore cannot show a success the state does not support:
 * page 2 offers 「完成」 only for `ACTIVE`, keeps the app's own retry/stop action for `ERROR`, and
 * says 「正在啟動防護…」 — with the button disabled — while the tunnel is still coming up.
 */
@Composable
fun OnboardingScreen(
    page: OnboardingPage,
    /** Whether the app really holds the VPN authorization, so page 1 never asks for it twice. */
    vpnAuthorized: Boolean,
    /** Whether another app's VPN owns the system's default network; shown as a plain notice only. */
    anotherVpnActive: Boolean,
    protectionState: ProtectionState,
    protectionError: ProtectionError?,
    isServiceRunning: Boolean,
    onPrimaryAction: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val actionLabel = when (page) {
        OnboardingPage.VPN_PERMISSION -> OnboardingPresentation.permissionActionLabel(vpnAuthorized)
        OnboardingPage.START_PROTECTION ->
            OnboardingPresentation.protectionActionLabel(protectionState, isServiceRunning)
    }
    val actionEnabled = when (page) {
        OnboardingPage.VPN_PERMISSION -> true
        OnboardingPage.START_PROTECTION -> OnboardingPresentation.protectionActionEnabled(protectionState)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        when (page) {
                            OnboardingPage.VPN_PERMISSION -> R.drawable.ic_dns
                            OnboardingPage.START_PROTECTION -> R.drawable.ic_shield
                        }
                    ),
                    // Decorative: the title right beside it carries the same meaning.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = OnboardingPresentation.stepLabel(page),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = OnboardingPresentation.pageTitle(page),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Text(
            text = OnboardingPresentation.pageBody(page),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        when (page) {
            OnboardingPage.VPN_PERMISSION -> PermissionFacts(
                vpnAuthorized = vpnAuthorized,
                anotherVpnActive = anotherVpnActive
            )

            OnboardingPage.START_PROTECTION -> ProtectionResult(
                state = protectionState,
                error = protectionError,
                isServiceRunning = isServiceRunning
            )
        }

        Button(
            onClick = onPrimaryAction,
            enabled = actionEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(actionLabel)
        }

        // Skippable at any point, and equally available on the second page: leaving the guide is
        // never gated on finishing it.
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(OnboardingPresentation.SKIP_ACTION)
        }
    }
}

/** Page 1's facts: the authorization the app really holds and, plainly, any other VPN in the way. */
@Composable
private fun PermissionFacts(vpnAuthorized: Boolean, anotherVpnActive: Boolean) {
    FactCard(
        value = OnboardingPresentation.permissionStatus(vpnAuthorized),
        detail = OnboardingPresentation.otherVpnNotice(anotherVpnActive),
        isWarning = anotherVpnActive
    )
}

/** Page 2's result: the headline is the protection state's own, never a claim of its own. */
@Composable
private fun ProtectionResult(
    state: ProtectionState,
    error: ProtectionError?,
    isServiceRunning: Boolean
) {
    val reason = OnboardingPresentation.protectionFailureReason(state, error)
    FactCard(
        value = OnboardingPresentation.protectionStatus(state, isServiceRunning),
        detail = listOfNotNull(reason, OnboardingPresentation.protectionFailureAdvice(state, error))
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n"),
        isWarning = state == ProtectionState.ERROR
    )
}

@Composable
private fun FactCard(value: String, detail: String?, isWarning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (isWarning) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Start
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
