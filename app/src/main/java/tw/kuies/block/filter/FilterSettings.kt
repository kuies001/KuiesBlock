package tw.kuies.block.filter

/**
 * Every input the local filter needs in order to publish a snapshot.
 *
 * One value holds the active tier, the rule sources, the user's manual allow/block rules and the
 * per-source bookkeeping (rule count, last success, last failure, ETag) that the UI reports. It is
 * immutable, so a snapshot build always works from one consistent set of inputs. Persistence lives
 * in `FilterSettingsStore`; this type has no Android or UI dependency.
 *
 * [profile] and [subscriptions] belong together: the main list of [profile] is carried by the
 * subscription whose id is [FilterSubscription.PROFILE_SUBSCRIPTION_ID] (see [builtInSubscription]).
 * Change the tier through [withProfile] so the main list is *replaced* rather than left behind —
 * the three tiers overlap heavily and are never stacked.
 */
data class FilterSettings(
    val profile: FilterProfile = FilterProfile.DEFAULT,
    val subscriptions: List<FilterSubscription> = listOf(
        FilterSubscription.forProfile(FilterProfile.DEFAULT)
    ),
    val manualAllow: List<ManualRule> = emptyList(),
    val manualBlock: List<ManualRule> = emptyList(),
    /** ETag of the stored copy of each subscription, used for conditional updates. */
    val etags: Map<String, String> = emptyMap()
) {

    /** The main list of [profile], when one is present. */
    val builtInSubscription: FilterSubscription?
        get() = subscriptions.firstOrNull { it.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID }

    /** Sources the user added. The built-in main list is never part of this list. */
    val customSubscriptions: List<FilterSubscription>
        get() = subscriptions.filter { it.id != FilterSubscription.PROFILE_SUBSCRIPTION_ID }

    fun etagFor(subscriptionId: String): String? = etags[subscriptionId]

    /**
     * Switches the active tier.
     *
     * The new tier's list replaces the old one and its download state starts from scratch: the stored
     * copy on disk belongs to the previous list, so it is not marked ready and its ETag is dropped.
     * Custom subscriptions are unaffected — they are independent sources, not tiers.
     */
    fun withProfile(profile: FilterProfile): FilterSettings = copy(
        profile = profile,
        subscriptions = listOf(FilterSubscription.forProfile(profile)) + customSubscriptions,
        etags = etags - FilterSubscription.PROFILE_SUBSCRIPTION_ID
    )

    /**
     * Adds [subscription], or replaces the entry with the same id.
     *
     * The main-list entry is fully defined by the active tier (id, name, URL and origin come from
     * [profile]); only its enabled flag, state and bookkeeping are taken from [subscription]. Use
     * [withProfile] to change which list is active.
     *
     * When an existing entry points at a different URL, its rule count, timestamp, error and ETag
     * describe content that belongs to the old URL, so they are cleared.
     */
    fun withSubscription(subscription: FilterSubscription): FilterSettings {
        val normalised = if (subscription.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID) {
            val asProfileList = FilterSubscription.forProfile(profile)
            subscription.copy(
                displayName = asProfileList.displayName,
                url = asProfileList.url,
                origin = SubscriptionOrigin.BUILT_IN
            )
        } else {
            subscription
        }

        val existing = subscriptions.firstOrNull { it.id == normalised.id }
        // A copy that described a different URL — including one for another tier — describes content
        // and bookkeeping that do not belong to what is stored now.
        val describedOtherUrl = subscription.url != normalised.url
        val urlChanged = describedOtherUrl || (existing != null && existing.url != normalised.url)
        val replacement = if (urlChanged) {
            normalised.copy(
                state = SubscriptionState.NOT_READY,
                ruleCount = 0,
                lastSuccessAtMillis = null,
                lastError = null
            )
        } else {
            normalised
        }

        val updated = if (existing == null) {
            subscriptions + replacement
        } else {
            subscriptions.map { if (it.id == replacement.id) replacement else it }
        }.distinctBy { it.id }
        return copy(
            subscriptions = updated,
            etags = if (urlChanged) etags - replacement.id else etags
        )
    }

    /**
     * Drops a user subscription and its ETag.
     *
     * The main list cannot be removed, only disabled ([FilterSubscription.enabled]); this returns the
     * settings unchanged for that id.
     */
    fun withoutSubscription(subscriptionId: String): FilterSettings {
        if (subscriptionId == FilterSubscription.PROFILE_SUBSCRIPTION_ID) return this
        return copy(
            subscriptions = subscriptions.filterNot { it.id == subscriptionId },
            etags = etags - subscriptionId
        )
    }

    /** Records the ETag of the stored copy, or forgets it when [etag] is null. */
    fun withEtag(subscriptionId: String, etag: String?): FilterSettings =
        copy(etags = if (etag == null) etags - subscriptionId else etags + (subscriptionId to etag))

    fun withManualAllow(rules: List<ManualRule>): FilterSettings = copy(manualAllow = distinct(rules))

    fun withManualBlock(rules: List<ManualRule>): FilterSettings = copy(manualBlock = distinct(rules))

    fun addManualAllow(rule: ManualRule): FilterSettings = withManualAllow(manualAllow + rule)

    fun addManualBlock(rule: ManualRule): FilterSettings = withManualBlock(manualBlock + rule)

    /** Removes every manual allow entry for [domain]; the UI deletes by domain, not by scope. */
    fun removeManualAllow(domain: String): FilterSettings =
        copy(manualAllow = manualAllow.filterNot { sameDomain(it.domain, domain) })

    fun removeManualBlock(domain: String): FilterSettings =
        copy(manualBlock = manualBlock.filterNot { sameDomain(it.domain, domain) })

    /**
     * True when [subscription]'s stored rules belong in the published snapshot.
     *
     * Already-downloaded rules stay in use after a failed update: a source in
     * [SubscriptionState.FAILED] that still has a stored rule count keeps contributing, because
     * dropping a working list would remove protection the user already has (the design's rule that a
     * failed update keeps the previous snapshot). [FilterSubscription.contributesRules] is stricter —
     * it also requires [SubscriptionState.READY] — and is the right check for an "up to date" label.
     * This predicate is the one the assembler uses.
     */
    fun contributesToSnapshot(subscription: FilterSubscription): Boolean =
        subscription.enabled && subscription.ruleCount > 0 &&
            (subscription.state == SubscriptionState.READY ||
                subscription.state == SubscriptionState.FAILED)

    /**
     * True when [subscription] is the main list of the active tier.
     *
     * Only the profile's own published locations count, so a stored copy from another tier (or with a
     * different origin) can never be added to the active list.
     */
    fun isProfileList(subscription: FilterSubscription): Boolean =
        subscription.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID &&
            subscription.origin == SubscriptionOrigin.BUILT_IN &&
            (subscription.url == profile.primaryUrl || subscription.url == profile.fallbackUrl)

    private fun sameDomain(a: String, b: String): Boolean =
        (DomainName.normalise(a) ?: a.trim().lowercase()) ==
            (DomainName.normalise(b) ?: b.trim().lowercase())

    /** Keeps the first entry per host and match scope; the engine compares canonical names anyway. */
    private fun distinct(rules: List<ManualRule>): List<ManualRule> =
        rules.distinctBy { (DomainName.normalise(it.domain) ?: it.domain.trim().lowercase()) to it.scope }

    companion object {
        /** Profile STANDARD, no manual rules, one built-in subscription and nothing downloaded yet. */
        val DEFAULTS = FilterSettings()

        /**
         * Defaults for a specific tier.
         *
         * Always use this (or [withProfile]) rather than `FilterSettings(profile = …)`, which would
         * keep the default tier's main list.
         */
        fun forProfile(profile: FilterProfile): FilterSettings = FilterSettings(
            profile = profile,
            subscriptions = listOf(FilterSubscription.forProfile(profile))
        )
    }
}
