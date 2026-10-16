package tw.kuies.block.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists [AppSettings] in a private SharedPreferences file that is excluded from backup.
 *
 * The store takes the `SharedPreferences` rather than the `Context` it came from (the production
 * constructor derives it), so the facts the first-run gate depends on can be unit tested on the JVM
 * with a fake, exactly like [tw.kuies.block.filter.FilterSettingsStore].
 */
class AppSettingsStore(private val prefs: SharedPreferences) {

    /** Production constructor: the app's private preference file, as with the other stores. */
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun load(): AppSettings = AppSettings(
        vpnAuthorizationGranted = prefs.getBoolean(KEY_VPN_AUTHORIZED, false),
        // Presence, not value: a recorded "no" is as much a sign of use as a recorded "yes".
        vpnAuthorizationRecorded = prefs.contains(KEY_VPN_AUTHORIZED),
        onboardingState = OnboardingState.fromId(prefs.getString(KEY_ONBOARDING_STATE, null)),
        protectionStarted = prefs.getBoolean(KEY_PROTECTION_STARTED, false)
    )

    fun setVpnAuthorizationGranted(granted: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_AUTHORIZED, granted).apply()
    }

    /**
     * Records how the guide was left.
     *
     * Written only when the guide was a first run: a replay from settings must leave the install's
     * own record exactly as it was, so [OnboardingGate.stateToPersist] answers null there and this
     * is never called.
     */
    fun setOnboardingState(state: OnboardingState) {
        prefs.edit().putString(KEY_ONBOARDING_STATE, state.id).apply()
    }

    /**
     * Records that protection was started on this install, once and for all.
     *
     * Never reset: the point of the flag is to remember that the install has been used, which stays
     * true after protection is switched off or its authorization revoked.
     */
    fun setProtectionStarted() {
        prefs.edit().putBoolean(KEY_PROTECTION_STARTED, true).apply()
    }

    companion object {
        const val PREFS_NAME = "kuies_block_settings"
        private const val KEY_VPN_AUTHORIZED = "vpn_authorization_granted"
        private const val KEY_ONBOARDING_STATE = "onboarding_state"
        private const val KEY_PROTECTION_STARTED = "protection_started"
    }
}
