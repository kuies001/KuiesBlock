package tw.kuies.block.data

import android.content.Context

/** Persists [AppSettings] in a private SharedPreferences file that is excluded from backup. */
class AppSettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        vpnAuthorizationGranted = prefs.getBoolean(KEY_VPN_AUTHORIZED, false)
    )

    fun setVpnAuthorizationGranted(granted: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_AUTHORIZED, granted).apply()
    }

    companion object {
        const val PREFS_NAME = "kuies_block_settings"
        private const val KEY_VPN_AUTHORIZED = "vpn_authorization_granted"
    }
}
