package tw.kuies.block.filter

/**
 * The three built-in interception strengths a user can choose.
 *
 * Exactly one main list is active at a time. The three HaGeZi tiers are already supersets of each
 * other, so stacking them would only add memory and make the effective strength impossible to
 * explain honestly to the user.
 *
 * The URLs are the ones HaGeZi's own README publishes for each tier (a jsDelivr mirror of the
 * GitHub repository); [fallbackUrl] is the upstream repository file itself. The lists are GPL-3.0
 * and are therefore **never bundled** with the app — the device downloads them at runtime, so this
 * project does not redistribute them.
 */
enum class FilterProfile(
    val id: String,
    val listName: String,
    val primaryUrl: String,
    val fallbackUrl: String,
    val ruleCountHint: Int
) {
    RELAXED(
        id = "relaxed",
        listName = "HaGeZi Multi LIGHT",
        primaryUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/light.txt",
        fallbackUrl = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt",
        ruleCountHint = 59_614
    ),
    STANDARD(
        id = "standard",
        listName = "HaGeZi Multi NORMAL",
        primaryUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/multi.txt",
        fallbackUrl = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/multi.txt",
        ruleCountHint = 161_902
    ),
    STRICT(
        id = "strict",
        listName = "HaGeZi Multi PRO++",
        primaryUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.plus.txt",
        fallbackUrl = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.plus.txt",
        ruleCountHint = 211_887
    );

    /** True when this tier is expected to block domains that can break app or site functionality. */
    val isAggressive: Boolean get() = this == STRICT

    companion object {
        /** The recommended default: broad protection without the aggressive tier's false positives. */
        val DEFAULT = STANDARD

        /** Parses a persisted id, falling back to [DEFAULT] so a stale id can never break start-up. */
        fun fromId(id: String?): FilterProfile =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Attribution the UI must be able to show for the built-in lists. */
object BuiltInFilterAttribution {
    const val HOME_PAGE = "https://github.com/hagezi/dns-blocklists"
    const val LICENCE_NAME = "GNU General Public License v3.0"
    const val LICENCE_URL = "https://github.com/hagezi/dns-blocklists/blob/main/LICENSE"
}

/** Where a subscription came from, which decides which hosts it may contact. */
enum class SubscriptionOrigin {
    /** Ships with the app: a fixed, known upstream host. */
    BUILT_IN,

    /** Added by the user: any public https host, but never a private or local address. */
    USER
}

/** Why a subscription is not currently contributing rules. */
enum class SubscriptionState {
    /** No content has ever been stored for this subscription. */
    NOT_READY,

    /** Content is stored and parsed. */
    READY,

    /** The last update attempt failed; the previous snapshot stays in use. */
    FAILED,

    /** The user turned it off; its rules are excluded from the snapshot. */
    DISABLED
}

/**
 * One rule source.
 *
 * [ruleCount] and [lastSuccessAtMillis] describe the content that is actually stored, so the UI never
 * has to guess whether a subscription contributed anything.
 */
data class FilterSubscription(
    val id: String,
    val displayName: String,
    val url: String,
    val origin: SubscriptionOrigin,
    val enabled: Boolean = true,
    val state: SubscriptionState = SubscriptionState.NOT_READY,
    val ruleCount: Int = 0,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null
) {
    /**
     * True when this source is enabled, has stored rules, and its last update succeeded.
     *
     * This is the flag for an "up to date" label. It is deliberately **not** the test for whether the
     * rules are in use: a failed update keeps the previously stored rules protecting the user, so the
     * snapshot is built from `FilterSettings.contributesToSnapshot`, which also accepts a source whose
     * last update failed while its stored rules remain.
     */
    val isUpToDate: Boolean get() = enabled && state == SubscriptionState.READY && ruleCount > 0

    /** The built-in subscription that carries [profile]'s main list. */
    companion object {
        const val PROFILE_SUBSCRIPTION_ID = "hagezi.profile"

        fun forProfile(profile: FilterProfile): FilterSubscription = FilterSubscription(
            id = PROFILE_SUBSCRIPTION_ID,
            displayName = profile.listName,
            url = profile.primaryUrl,
            origin = SubscriptionOrigin.BUILT_IN,
            enabled = true
        )
    }
}
