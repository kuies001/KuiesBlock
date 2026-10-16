package tw.kuies.block.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.ManualRuleDraft
import tw.kuies.block.filter.MatchScope

/**
 * The user's manual allow and block lists: the two tabs, the add field and the rows that can be
 * deleted.
 *
 * The section is a pure function of the rules it is handed. It never reads the settings store, never
 * writes one, and never touches the engine: adding or removing a rule is reported through the
 * callbacks and the screen above it decides what that means for persistence. It also does its own
 * validation — [ManualRuleDraft.parse] runs here, and a refused entry becomes the sentence from
 * [AllowBlockPresentation] instead of a rule, so the lists can never contain something the user did
 * not mean.
 *
 * Its own state — the selected tab, the typed domain, the match scope, the search text and the error
 * line — is held locally. Nothing outside sees it, and nothing outside can leave it inconsistent.
 */
@Composable
fun AllowBlockSection(
    allow: List<ManualRule>,
    block: List<ManualRule>,
    onAddAllow: (ManualRule) -> Unit,
    onAddBlock: (ManualRule) -> Unit,
    onRemoveAllow: (ManualRule) -> Unit,
    onRemoveBlock: (ManualRule) -> Unit,
    modifier: Modifier = Modifier
) {
    var blocking by rememberSaveable { mutableStateOf(false) }
    var exactScope by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val rules = if (blocking) block else allow
    val scope = if (exactScope) MatchScope.EXACT else MatchScope.SUBDOMAIN

    val add: () -> Unit = {
        when (val result = ManualRuleDraft.parse(draft, scope, rules)) {
            is ManualRuleDraft.Result.Accepted -> {
                if (blocking) onAddBlock(result.rule) else onAddAllow(result.rule)
                draft = ""
                // A search that hid the new rule would look like the add had silently failed.
                query = ""
                error = null
            }

            is ManualRuleDraft.Result.Rejected ->
                error = AllowBlockPresentation.rejectionMessage(result.reason)
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
                text = AllowBlockPresentation.SECTION_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !blocking,
                    onClick = {
                        blocking = false
                        error = null
                    },
                    label = { Text(AllowBlockPresentation.TAB_ALLOW) }
                )
                FilterChip(
                    selected = blocking,
                    onClick = {
                        blocking = true
                        error = null
                    },
                    label = { Text(AllowBlockPresentation.TAB_BLOCK) }
                )
            }

            if (!blocking) {
                Text(
                    text = AllowBlockPresentation.ALLOW_UPSTREAM_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error != null,
                label = { Text(AllowBlockPresentation.DOMAIN_FIELD_LABEL) },
                placeholder = { Text(AllowBlockPresentation.DOMAIN_PLACEHOLDER) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() })
            )

            Text(
                text = AllowBlockPresentation.SCOPE_LABEL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !exactScope,
                    onClick = { exactScope = false },
                    label = { Text(AllowBlockPresentation.scopeLabel(MatchScope.SUBDOMAIN)) }
                )
                FilterChip(
                    selected = exactScope,
                    onClick = { exactScope = true },
                    label = { Text(AllowBlockPresentation.scopeLabel(MatchScope.EXACT)) }
                )
            }

            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = add,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(AllowBlockPresentation.ADD_LABEL)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(AllowBlockPresentation.SEARCH_PLACEHOLDER) }
            )

            val visible = ManualRuleDraft.search(rules, query)
            when {
                // An empty list and a search that matched nothing are different answers, so they do
                // not share a message.
                rules.isEmpty() -> Text(
                    text = AllowBlockPresentation.emptyLabel(blocking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                visible.isEmpty() -> Text(
                    text = AllowBlockPresentation.NO_MATCH,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> visible.forEach { rule ->
                    RuleRow(
                        rule = rule,
                        onRemove = {
                            if (blocking) onRemoveBlock(rule) else onRemoveAllow(rule)
                        }
                    )
                }
            }
        }
    }
}

/** One stored rule: the domain, how far it reaches, and the way to delete it. */
@Composable
private fun RuleRow(rule: ManualRule, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.domain,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = AllowBlockPresentation.scopeLabel(rule.scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onRemove) {
            Text(
                text = AllowBlockPresentation.REMOVE_LABEL,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
