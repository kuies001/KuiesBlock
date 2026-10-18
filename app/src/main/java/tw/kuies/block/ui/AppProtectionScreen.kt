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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
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

/** The two views of the same exclusion set: every app, or only the ones that bypass the tunnel. */
private enum class AppProtectionTab { ALL, EXCLUDED }

/**
 * The independent "個別 App 防護" page.
 *
 * The switch is inverted from the exclusion store on purpose: a user thinks "protect this app",
 * while the VPN builder needs the list of apps that must bypass the tunnel. Both tabs read the same
 * stored set, so a change made in one is visible in the other immediately.
 *
 * The page itself owns no settings: it reports taps upward and renders what it is given. In
 * particular, searching, switching tabs and reloading the list never write anything — only a switch
 * or the one confirmed batch action can.
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
    var tab by rememberSaveable { mutableStateOf(AppProtectionTab.ALL) }
    var menuExpanded by remember { mutableStateOf(false) }
    var restoreDialogVisible by remember { mutableStateOf(false) }

    // The excluded tab is built from the stored set rather than from the query result, so an app that
    // has since been uninstalled — or a list that failed to load — still appears and can be restored.
    val visibleApps = remember(apps, excludedPackages, tab) {
        when (tab) {
            AppProtectionTab.ALL -> apps
            AppProtectionTab.EXCLUDED -> excludedEntries(apps, excludedPackages)
        }
    }
    val filtered = remember(visibleApps, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            visibleApps
        } else {
            visibleApps.filter {
                it.label.contains(needle, ignoreCase = true) ||
                    it.packageName.contains(needle, ignoreCase = true)
            }
        }
    }
    val emptyText = if (tab == AppProtectionTab.EXCLUDED && excludedPackages.isEmpty()) {
        R.string.app_protection_excluded_empty
    } else {
        R.string.app_protection_empty
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
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.app_protection_menu),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.app_protection_rerun)) },
                        // With nothing excluded the confirmed action would clear nothing and rebuild
                        // nothing, so the only batch entry is offered exactly when it can do work.
                        enabled = excludedPackages.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            restoreDialogVisible = true
                        }
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.app_protection_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        PrimaryTabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = Color.Transparent,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Tab(
                selected = tab == AppProtectionTab.ALL,
                onClick = { tab = AppProtectionTab.ALL },
                text = { Text(stringResource(R.string.app_protection_tab_all)) }
            )
            Tab(
                selected = tab == AppProtectionTab.EXCLUDED,
                onClick = { tab = AppProtectionTab.EXCLUDED },
                text = {
                    Text(
                        text = stringResource(
                            R.string.app_protection_tab_excluded,
                            excludedPackages.size
                        )
                    )
                }
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.app_protection_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (loading && tab == AppProtectionTab.ALL) {
                Column(
                    modifier = Modifier.fillMaxSize(),
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
            } else {
                if (loadFailed) {
                    // The stored exclusions stay usable below; only the names and icons are missing.
                    Text(
                        text = stringResource(R.string.app_protection_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(emptyText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                } else {
                    LazyColumn(
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
        }
    }

    if (restoreDialogVisible) {
        AlertDialog(
            onDismissRequest = { restoreDialogVisible = false },
            title = { Text(stringResource(R.string.app_protection_rerun_title)) },
            text = { Text(stringResource(R.string.app_protection_rerun_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreDialogVisible = false
                        onRestoreAll()
                    }
                ) {
                    Text(stringResource(R.string.app_protection_rerun_confirm))
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

/**
 * The excluded packages as listable rows.
 *
 * An excluded package the launcher query no longer returns is not a stale setting to drop: the user
 * may restore it, and a reinstall should find the previous choice waiting. It therefore appears with
 * its package name as the label and the default icon.
 */
private fun excludedEntries(
    apps: List<InstalledApp>,
    excludedPackages: Set<String>
): List<InstalledApp> {
    val known = apps.associateBy { it.packageName }
    return excludedPackages
        .map { packageName ->
            known[packageName] ?: InstalledApp(
                packageName = packageName,
                label = packageName,
                icon = null
            )
        }
        .sortedWith(
            compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName }
        )
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
                Icon(
                    painter = painterResource(R.drawable.ic_app_default),
                    // Decorative: the row's label carries the same identity.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
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
            // An uninstalled entry's label is its package name, so the second line would repeat it.
            if (app.label != app.packageName) {
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = protected,
            onCheckedChange = onSetProtected
        )
    }
}
