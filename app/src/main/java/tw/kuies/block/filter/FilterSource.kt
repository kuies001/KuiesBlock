package tw.kuies.block.filter

private const val HAGEZI_HOME = "https://github.com/hagezi/dns-blocklists"
private const val HAGEZI_LICENCE = "GNU GPL-3.0"

/**
 * The four choices the settings page offers.
 *
 * The three built-in strengths are presets: each one uses exactly the HaGeZi main list it names and
 * nothing else. [CUSTOM] is the only mode in which the user's source selection decides what is
 * published, which is what keeps the tiers isolated from each other.
 */
enum class FilterMode(val id: String, val profile: FilterProfile?) {
    RELAXED("relaxed", FilterProfile.RELAXED),
    STANDARD("standard", FilterProfile.STANDARD),
    STRICT("strict", FilterProfile.STRICT),
    CUSTOM("custom", null);

    /** The built-in source a preset mode is fixed to; null for [CUSTOM]. */
    val source: FilterSource? get() = profile?.let(FilterSource::forProfile)

    companion object {
        val DEFAULT = STANDARD

        fun fromId(id: String?): FilterMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        fun forProfile(profile: FilterProfile): FilterMode = when (profile) {
            FilterProfile.RELAXED -> RELAXED
            FilterProfile.STANDARD -> STANDARD
            FilterProfile.STRICT -> STRICT
        }
    }
}

/**
 * One source the user can enable in custom mode.
 *
 * Only definitions live here: a source is a name, an official https address and its attribution. No
 * list content is bundled with the app; the device downloads each list and [FilterSubscription]
 * records what that download produced.
 *
 * The three HaGeZi Multi tiers are the same lists the preset modes use and share the legacy
 * `hagezi.profile` subscription id, so an upgrade keeps using the file it already downloaded. They are
 * mutually exclusive ([isMultiTier]); every other source may be combined with them.
 */
enum class FilterSource(
    val id: String,
    val displayName: String,
    val purpose: String,
    val maintainer: String,
    val maintainerHome: String,
    val licenceName: String,
    val primaryUrl: String,
    val fallbackUrl: String,
    val subscriptionId: String,
    /** Non-null for a HaGeZi Multi tier, which is the only kind that may be selected once. */
    val profile: FilterProfile? = null
) {
    HAGEZI_LIGHT(
        id = "hagezi.light",
        displayName = "HaGeZi Multi LIGHT",
        purpose = "只擋最明顯的廣告與追蹤網域，誤擋機會最低。",
        maintainer = "HaGeZi",
        maintainerHome = HAGEZI_HOME,
        licenceName = HAGEZI_LICENCE,
        primaryUrl = FilterProfile.RELAXED.primaryUrl,
        fallbackUrl = FilterProfile.RELAXED.fallbackUrl,
        subscriptionId = FilterSubscription.PROFILE_SUBSCRIPTION_ID,
        profile = FilterProfile.RELAXED
    ),
    HAGEZI_NORMAL(
        id = "hagezi.normal",
        displayName = "HaGeZi Multi NORMAL",
        purpose = "廣泛攔截廣告與追蹤網域，日常使用的建議選項。",
        maintainer = "HaGeZi",
        maintainerHome = HAGEZI_HOME,
        licenceName = HAGEZI_LICENCE,
        primaryUrl = FilterProfile.STANDARD.primaryUrl,
        fallbackUrl = FilterProfile.STANDARD.fallbackUrl,
        subscriptionId = FilterSubscription.PROFILE_SUBSCRIPTION_ID,
        profile = FilterProfile.STANDARD
    ),
    HAGEZI_PRO_PLUS(
        id = "hagezi.pro.plus",
        displayName = "HaGeZi Multi PRO++",
        purpose = "攔截最積極，可能影響部分網站或 App 的功能。",
        maintainer = "HaGeZi",
        maintainerHome = HAGEZI_HOME,
        licenceName = HAGEZI_LICENCE,
        primaryUrl = FilterProfile.STRICT.primaryUrl,
        fallbackUrl = FilterProfile.STRICT.fallbackUrl,
        subscriptionId = FilterSubscription.PROFILE_SUBSCRIPTION_ID,
        profile = FilterProfile.STRICT
    ),
    HAGEZI_TIF(
        id = "hagezi.tif",
        displayName = "HaGeZi TIF (Mini)",
        purpose = "惡意與威脅情報網域；採官方 mini 版本以符合目前的清單大小上限。",
        maintainer = "HaGeZi",
        maintainerHome = HAGEZI_HOME,
        licenceName = HAGEZI_LICENCE,
        primaryUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/tif.mini.txt",
        fallbackUrl = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.mini.txt",
        subscriptionId = "hagezi.tif"
    ),
    ADGUARD_DNS(
        id = "adguard.dns",
        displayName = "AdGuard DNS Filter",
        purpose = "AdGuard 維護的 DNS 層廣告與追蹤清單。",
        maintainer = "AdGuard",
        maintainerHome = "https://github.com/AdguardTeam/AdguardSDNSFilter",
        licenceName = "GNU GPL-3.0",
        primaryUrl = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
        fallbackUrl = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
        subscriptionId = "adguard.dns"
    ),
    OISD(
        id = "oisd.big",
        displayName = "OISD",
        purpose = "OISD 的大型網域封鎖清單，涵蓋廣告、追蹤與惡意網域。",
        maintainer = "Stephan van Ruth",
        maintainerHome = "https://oisd.nl",
        licenceName = "GNU GPL-3.0",
        primaryUrl = "https://big.oisd.nl/",
        fallbackUrl = "https://big.oisd.nl/",
        subscriptionId = "oisd.big"
    ),
    HAGEZI_NSFW(
        id = "hagezi.nsfw",
        displayName = "HaGeZi NSFW",
        purpose = "成人內容網域；預設不啟用。",
        maintainer = "HaGeZi",
        maintainerHome = HAGEZI_HOME,
        licenceName = HAGEZI_LICENCE,
        primaryUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/nsfw.txt",
        fallbackUrl = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/nsfw.txt",
        subscriptionId = "hagezi.nsfw"
    ),
    HAGEZI_SOCIAL(
        id = "hagezi.social",
        displayName = "HaGeZi Social",
        purpose = "社群網站網域；預設不啟用。",
        maintainer = "HaGeZi",
        maintainerHome = HAGEZI_HOME,
        licenceName = HAGEZI_LICENCE,
        primaryUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/social.txt",
        fallbackUrl = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/social.txt",
        subscriptionId = "hagezi.social"
    );

    val isMultiTier: Boolean get() = profile != null

    /** The subscription entry this source uses before anything has been downloaded. */
    fun subscription(): FilterSubscription = FilterSubscription(
        id = subscriptionId,
        displayName = displayName,
        url = primaryUrl,
        origin = SubscriptionOrigin.BUILT_IN,
        enabled = true
    )

    /**
     * True when [subscription] is this source's own record.
     *
     * Both the id and the URL have to agree, so a stored copy of another list (or one pointing
     * somewhere else) can never be treated as this source.
     */
    fun matches(subscription: FilterSubscription): Boolean =
        subscription.origin == SubscriptionOrigin.BUILT_IN &&
            subscription.id == subscriptionId &&
            (subscription.url == primaryUrl || subscription.url == fallbackUrl)

    companion object {
        val MULTI_TIERS: List<FilterSource> = entries.filter { it.isMultiTier }

        fun fromId(id: String?): FilterSource? = entries.firstOrNull { it.id == id }

        fun forProfile(profile: FilterProfile): FilterSource = when (profile) {
            FilterProfile.RELAXED -> HAGEZI_LIGHT
            FilterProfile.STANDARD -> HAGEZI_NORMAL
            FilterProfile.STRICT -> HAGEZI_PRO_PLUS
        }

        fun forSubscription(subscription: FilterSubscription): FilterSource? =
            entries.firstOrNull { it.matches(subscription) }

        fun forSubscriptionId(id: String): FilterSource? =
            entries.firstOrNull { it.subscriptionId == id }
    }
}
