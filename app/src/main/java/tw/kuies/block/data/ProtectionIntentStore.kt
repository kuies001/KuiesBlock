package tw.kuies.block.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The two persisted facts a restart has to be judged by, kept in their own file so
 * [AppSettings] — and with it the first-run gate and its tests — keeps exactly the shape it had.
 *
 * They answer different questions on purpose:
 *  * [bootAutostartEnabled] is a **preference** the user sets: "give me protection back after a
 *    restart". It defaults to off.
 *  * [protectionDesiredOn] is an **intent** recorded from the user's last word on the home switch.
 *    It is what stops a restart from undoing a deliberate "turn protection off": a user who switched
 *    it off has this false, and no boot may turn it back on for them.
 */
class ProtectionIntentStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /** Off unless the user asked for it, so a fresh install never starts anything by itself. */
    fun bootAutostartEnabled(): Boolean = prefs.getBoolean(KEY_BOOT_AUTOSTART, false)

    fun setBootAutostartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BOOT_AUTOSTART, enabled).apply()
    }

    /** Whether the user's last action on the protection switch was to turn it on. */
    fun protectionDesiredOn(): Boolean = prefs.getBoolean(KEY_PROTECTION_DESIRED, false)

    fun setProtectionDesiredOn(desiredOn: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECTION_DESIRED, desiredOn).apply()
    }

    companion object {
        const val PREFS_NAME = "kuies_block_protection_intent"
        private const val KEY_BOOT_AUTOSTART = "boot_autostart_enabled"
        private const val KEY_PROTECTION_DESIRED = "protection_desired_on"
    }
}
