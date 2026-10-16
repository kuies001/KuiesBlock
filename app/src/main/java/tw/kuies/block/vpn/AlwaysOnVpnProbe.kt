package tw.kuies.block.vpn

import android.content.Context
import android.provider.Settings

/**
 * Reads whether Android's **Always-on VPN** is set to KuiesBlock.
 *
 * This is the platform's own way of restoring a VPN after a restart: the system starts the service
 * itself, with no broadcast involved. That makes it strictly more reliable than `BOOT_COMPLETED` —
 * which a force-stopped app never receives — and it is also why the app must **not** start the tunnel
 * when this is on, or the same tunnel would be established twice.
 *
 * The comparison is pure so it can be unit tested; only [readConfiguredApp] touches Android, and a
 * failure to read the setting degrades to "not always-on", which is the safe direction: the app then
 * falls back to its own best-effort attempt instead of assuming the system has it covered.
 */
object AlwaysOnVpnProbe {

    /** The secure setting that records which package is the always-on VPN. */
    const val CONFIGURED_APP_SETTING = "always_on_vpn_app"

    fun isEnabledFor(packageName: String, configuredApp: String?): Boolean =
        !configuredApp.isNullOrBlank() && configuredApp == packageName

    /**
     * The package the system currently lists as the always-on VPN, or null when none is set.
     *
     * Read-only and permission-free: it neither changes the setting nor asks for one.
     */
    fun readConfiguredApp(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, CONFIGURED_APP_SETTING)
    }.getOrNull()
}
