package tw.kuies.block.power

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery-optimisation exemption: its real state, and the one system screen that can grant
 * it.
 *
 * [isExempt] asks the platform every time. Nothing here stores a "the user tapped allow" answer, so an
 * exemption revoked in Android's settings reads as not held the next time the home screen resumes.
 *
 * The request intent is built here and only ever launched by the caller's tap: it opens Android's own
 * screen or dialog and never answers for the user. When the platform offers no handler the caller is
 * told so — nothing in this object can crash the app.
 */
object BatteryOptimization {

    /** The authoritative answer. A device without a PowerManager cannot hold the exemption. */
    fun isExempt(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /**
     * The direct request for this package. Kept separate from [requestIntent] so its action and data
     * can be checked without a device that resolves it.
     */
    fun directRequestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )

    /** Android's exemption list, for devices that do not handle the direct request. */
    fun settingsListIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * The screen to open: the direct request when this device handles it, otherwise the list where
     * the user can pick the app themselves. Null means neither screen is available.
     */
    fun requestIntent(context: Context): Intent? =
        listOf(directRequestIntent(context), settingsListIntent())
            .firstOrNull { resolves(context, it) }

    /**
     * Opens [requestIntent] and reports whether a screen was opened. Every failure the platform can
     * raise here — no handler, or a permission the device refuses — becomes `false`, never a crash.
     */
    fun openRequest(context: Context): Boolean {
        val intent = requestIntent(context) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun resolves(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(intent, 0) != null
    }.getOrDefault(false)
}
