package tw.kuies.block.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.kuies.block.R
import tw.kuies.block.data.InstalledApp

/** What the management page should say about the last exclusion change. */
enum class AppProtectionStatus {
    IDLE,
    APPLYING,
    SAVED,
    APPLIED,
    FAILED
}

/**
 * The independent "個別 App 防護" page.
 *
 * The switch is inverted from the exclusion store on purpose: a user thinks "protect this app",
 * while the VPN builder needs the list of apps that must bypass the tunnel.
 */
@Composable
fun AppProtectionScreen(
    apps: List<InstalledApp>,
    loading: Boolean,
    loadFailed: Boolean,
    excludedPackages: Set<String>,
    status: AppProtectionStatus,
    protectionRunning: Boolean,
    onSetProtected: (packageName: String, protected: Boolean) -> Unit,
    onRestoreAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var restoreDialogVisible by remember { mutableStateOf(false) }
    val filtered = remember(apps, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            apps
        } else {
            apps.filter { it.label.contains(needle, ignoreCase = true) }
        }
    }
    val statusText = when {
        status == AppProtectionStatus.APPLYING -> stringResource(R.string.app_protection_applying)
        status == AppProtectionStatus.FAILED -> stringResource(R.string.app_protection_failed)
        !protectionRunning -> stringResource(R.string.app_protection_not_running)
        status == AppProtectionStatus.SAVED -> stringResource(R.string.app_protection_saved)
        status == AppProtectionStatus.APPLIED -> stringResource(R.string.app_protection_applied)
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.app_protection_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(R.string.app_protection_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.app_protection_excluded_count,
                    excludedPackages.size
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (excludedPackages.isNotEmpty()) {
                TextButton(onClick = { restoreDialogVisible = true }) {
                    Text(stringResource(R.string.app_protection_restore_all))
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.app_protection_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        statusText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (status == AppProtectionStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        when {
            loading -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.app_protection_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            loadFailed -> Text(
                text = stringResource(R.string.app_protection_load_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 24.dp)
            )

            filtered.isEmpty() -> Text(
                text = stringResource(R.string.app_protection_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppProtectionRow(
                        app = app,
                        protected = app.packageName !in excludedPackages,
                        onSetProtected = { protected ->
                            onSetProtected(app.packageName, protected)
                        }
                    )
                }
            }
        }
    }

    if (restoreDialogVisible) {
        AlertDialog(
            onDismissRequest = { restoreDialogVisible = false },
            title = { Text(stringResource(R.string.app_protection_restore_title)) },
            text = { Text(stringResource(R.string.app_protection_restore_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreDialogVisible = false
                        onRestoreAll()
                    }
                ) {
                    Text(stringResource(R.string.app_protection_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreDialogVisible = false }) {
                    Text(stringResource(R.string.app_protection_cancel))
                }
            }
        )
    }
}

@Composable
private fun AppProtectionRow(
    app: InstalledApp,
    protected: Boolean,
    onSetProtected: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.label.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = protected,
            onCheckedChange = onSetProtected
        )
    }
}
