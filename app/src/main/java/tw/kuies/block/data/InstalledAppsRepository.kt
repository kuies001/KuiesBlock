package tw.kuies.block.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/** One installed launcher app the user can manage. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?
)

/** A package query either produced a list or failed; a failure must never clear stored choices. */
data class InstalledAppsResult(
    val apps: List<InstalledApp>,
    val failed: Boolean = false
)

/**
 * Reads the user-visible launcher apps with the minimum package-visibility surface.
 *
 * The manifest declares only the MAIN/LAUNCHER query, so this does not require QUERY_ALL_PACKAGES.
 * Loading and icon decoding run on a caller-owned background executor; this object never touches the
 * main thread.
 */
object InstalledAppsRepository {

    fun load(context: Context): InstalledAppsResult {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            return InstalledAppsResult(emptyList(), failed = true)
        }

        val apps = resolved.asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                // The app itself is never offered: excluding it would remove KuiesBlock's own DNS
                // path from the tunnel and is not a meaningful "protect this app" choice.
                if (packageName == context.packageName) return@mapNotNull null
                val label = info.loadLabel(manager)?.toString()?.takeIf { it.isNotBlank() }
                    ?: packageName
                val icon = runCatching {
                    info.loadIcon(manager)?.toBitmap(ICON_SIZE, ICON_SIZE)
                }.getOrNull()
                InstalledApp(packageName = packageName, label = label, icon = icon)
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy { it.packageName }
            )
            .toList()

        return InstalledAppsResult(apps)
    }

    private const val ICON_SIZE = 96
}
