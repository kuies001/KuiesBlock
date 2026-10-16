package tw.kuies.block.data

/**
 * The two decisions the first-run guide needs, as pure functions.
 *
 * Kept free of Compose, of any Context and of any VPN type, so which install sees the guide and what
 * leaving it writes can be unit tested on the JVM — including the cases that must *not* show it.
 * The consent dialog's own result code is [tw.kuies.block.vpn.VpnAuthorization.consentGranted]'s.
 */
object OnboardingGate {

    /**
     * Whether the guide may appear by itself.
     *
     * A genuinely new install is required, and [settings] alone is never enough to establish that:
     * an install upgrading from a build without this guide has no onboarding record either, so the
     * guide must ALSO have no other evidence of use. The conditions are independent:
     *
     *  * [AppSettings.onboardingState] is [OnboardingState.NOT_SEEN] — the guide has not been
     *    finished or skipped here;
     *  * nothing was ever recorded for the VPN authorization, granted or refused — the app records
     *    the platform's answer on every resume, so *any* install that has been opened before holds
     *    the record, and only a first run holds none. This is the fact that recognises an upgrading
     *    user who never granted the permission, which [AppSettings.vpnAuthorizationGranted] alone
     *    cannot: for them it is false either way;
     *  * [AppSettings.vpnAuthorizationGranted] is false, so a granted install is never treated as new
     *    even if its record were somehow missing;
     *  * [AppSettings.protectionStarted] is false — protection was never asked to start, which stays
     *    true even after the authorization has been revoked in Android's settings, where the granted
     *    flag goes back to false;
     *  * [filterSettingsStored] is false — `FilterSettingsStore` holds no stored settings, so the
     *    user never picked a strength, added a source or wrote a rule. A legacy 關閉 counts as stored
     *    settings: that install has been used.
     *
     * The cost of the strictness is deliberate: an install that was opened once and then abandoned
     * mid-guide is not offered the guide again on its next start. It is one tap away in settings, and
     * the alternative — forcing the guide on an existing user — is what must not happen.
     */
    fun shouldShowAutomatically(settings: AppSettings, filterSettingsStored: Boolean): Boolean =
        settings.onboardingState == OnboardingState.NOT_SEEN &&
            !settings.vpnAuthorizationRecorded &&
            !settings.vpnAuthorizationGranted &&
            !settings.protectionStarted &&
            !filterSettingsStored

    /**
     * What leaving the guide writes, or null when nothing at all must be written.
     *
     * [replay] is the guide opened from settings. A replay is read-only: it neither clears nor
     * re-records the install's own state, so a user who opens the guide again cannot make it come
     * back by itself, and finishing a replay cannot turn a recorded "skipped" into "completed".
     */
    fun stateToPersist(replay: Boolean, completed: Boolean): OnboardingState? = when {
        replay -> null
        completed -> OnboardingState.COMPLETED
        else -> OnboardingState.SKIPPED
    }
}
