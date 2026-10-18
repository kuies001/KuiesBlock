package tw.kuies.block.data

/**
 * Small, non-sensitive settings blob.
 *
 * KuiesBlock deliberately keeps no accounts, identifiers, or history, so this is the whole
 * persisted state besides the DNS configuration and the filter settings.
 */
data class AppSettings(
    /** Last known VPN authorization state, used only to render the UI before `prepare()` is re-checked. */
    val vpnAuthorizationGranted: Boolean = false,

    /**
     * Whether an authorization answer has ever been recorded here — granted **or** refused.
     *
     * This, and not [vpnAuthorizationGranted], is what tells a new install from an existing one: the
     * app records the platform's answer on every resume, so any install that has been opened before
     * this version holds the record, while one that has never run holds nothing at all. A refused
     * answer counts, because it too is proof the install has been used.
     */
    val vpnAuthorizationRecorded: Boolean = false,

    /**
     * How far the first-run guide got on this install.
     *
     * Recorded so the guide is not shown again by itself, and so a replay from settings can be told
     * apart from a genuine first run.
     */
    val onboardingState: OnboardingState = OnboardingState.NOT_SEEN,

    /**
     * Whether protection was ever actually started on this install.
     *
     * One of the facts that decides a genuinely new install: it stays true even after the user
     * revokes the VPN authorization in Android's settings, where [vpnAuthorizationGranted] would go
     * back to false and the install would otherwise look untouched.
     */
    val protectionStarted: Boolean = false,

    /** The user's theme choice; [ThemeMode.SYSTEM] for a new or older install with no value stored. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)
