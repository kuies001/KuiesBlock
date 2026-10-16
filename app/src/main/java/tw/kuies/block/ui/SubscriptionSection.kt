package tw.kuies.block.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionDraft
import tw.kuies.block.filter.SubscriptionOrigin

/**
 * The custom subscription sources the user added by hand, plus the field that adds one.
 *
 * This composable is a pure function of its parameters: it reads no preference, downloads nothing and
 * touches no storage. Every value it shows comes from [subscriptions] and every change is reported to
 * one of the callbacks, so the screen cannot start a download or invent a source on its own.
 *
 * The "add" field parses with [SubscriptionDraft.parse] here, in the same place the user is looking,
 * so a refused URL is explained where it was typed and nothing is added: a rejection never reaches
 * [onAdd]. Its own two text fields and the refusal sentence are the only state it owns.
 *
 * A source is never described by one line. The last attempt ([SubscriptionPresentation.updateResultLabel])
 * and whether the rules are in use ([SubscriptionPresentation.rulesInEffectLabel]) are shown
 * separately, because a failed update still filters with the copy already on disk.
 *
 * @param busyId the id of the source whose download is running, if any; that row says so and cannot
 *   start a second update.
 * @param onDelete is only offered for a source the user added — the built-in main list can be disabled
 *   but never removed, so a delete button for it would be a lie.
 */
@Composable
fun SubscriptionSection(
    subscriptions: List<FilterSubscription>,
    busyId: String?,
    onAdd: (FilterSubscription) -> Unit,
    onSetEnabled: (FilterSubscription, Boolean) -> Unit,
    onUpdate: (FilterSubscription) -> Unit,
    onDelete: (FilterSubscription) -> Unit,
    modifier: Modifier = Modifier
) {
    var urlInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var refusal by remember { mutableStateOf<String?>(null) }

    val customSourceCount = subscriptions.count { it.origin == SubscriptionOrigin.USER }

    fun addSource() {
        when (val result = SubscriptionDraft.parse(urlInput, nameInput, subscriptions)) {
            is SubscriptionDraft.Result.Accepted -> {
                onAdd(result.subscription)
                urlInput = ""
                nameInput = ""
                refusal = null
            }

            is SubscriptionDraft.Result.Rejected -> {
                // The user's text stays where it is so they can correct it; nothing is added.
                refusal = SubscriptionPresentation.rejectionLabel(result.reason)
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
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
                text = SubscriptionPresentation.SECTION_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = SubscriptionPresentation.SECTION_BODY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = SubscriptionPresentation.sourceCountLabel(customSourceCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = urlInput,
                onValueChange = {
                    urlInput = it
                    refusal = null
                },
                label = { Text(SubscriptionPresentation.ADD_URL_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    refusal = null
                },
                label = { Text(SubscriptionPresentation.ADD_NAME_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { addSource() }, modifier = Modifier.fillMaxWidth()) {
                Text(SubscriptionPresentation.ADD_ACTION)
            }
            refusal?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (subscriptions.isEmpty()) {
                Text(
                    text = SubscriptionPresentation.EMPTY_STATE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val now = System.currentTimeMillis()
            subscriptions.forEach { subscription ->
                Spacer(modifier = Modifier.height(4.dp))
                SubscriptionRow(
                    subscription = subscription,
                    busy = subscription.id == busyId,
                    now = now,
                    onSetEnabled = onSetEnabled,
                    onUpdate = onUpdate,
                    onDelete = onDelete
                )
            }
        }
    }
}

/** One source: what it is, what state it is in, and the three things the user can do to it. */
@Composable
private fun SubscriptionRow(
    subscription: FilterSubscription,
    busy: Boolean,
    now: Long,
    onSetEnabled: (FilterSubscription, Boolean) -> Unit,
    onUpdate: (FilterSubscription) -> Unit,
    onDelete: (FilterSubscription) -> Unit
) {
    val inEffect = SubscriptionPresentation.rulesInEffect(subscription)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = SubscriptionPresentation.sourceName(subscription),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = SubscriptionPresentation.sourceHost(subscription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = SubscriptionPresentation.originLabel(subscription.origin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LabeledValue(
            label = SubscriptionPresentation.FIELD_ENABLED,
            value = SubscriptionPresentation.enabledLabel(subscription.enabled)
        )
        LabeledValue(
            label = SubscriptionPresentation.FIELD_RULES,
            value = SubscriptionPresentation.ruleCountLabel(subscription)
        )
        LabeledValue(
            label = SubscriptionPresentation.FIELD_LAST_UPDATE,
            value = SubscriptionPresentation.lastUpdateLabel(subscription, now)
        )
        LabeledValue(
            label = SubscriptionPresentation.FIELD_RESULT,
            value = SubscriptionPresentation.updateResultLabel(subscription, now)
        )
        Text(
            text = SubscriptionPresentation.rulesInEffectLabel(subscription),
            style = MaterialTheme.typography.bodySmall,
            // Mint when the rules really are in use, a muted tone when they are not: the same
            // distinction the sentence makes, so it cannot read the wrong way at a glance.
            color = if (inEffect) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onSetEnabled(subscription, !subscription.enabled) }) {
                Text(SubscriptionPresentation.toggleActionLabel(subscription.enabled))
            }
            TextButton(onClick = { onUpdate(subscription) }, enabled = !busy) {
                Text(SubscriptionPresentation.updateActionLabel(busy))
            }
            if (subscription.origin == SubscriptionOrigin.USER) {
                TextButton(onClick = { onDelete(subscription) }) {
                    Text(
                        text = SubscriptionPresentation.DELETE_ACTION,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
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
