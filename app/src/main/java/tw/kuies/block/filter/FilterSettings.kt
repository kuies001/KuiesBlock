package tw.kuies.block.filter

/**
 * Every input the local filter needs in order to publish a snapshot.
 *
 * One value holds the active mode, the rule sources, the user's stored manual allow/block rules and
 * the per-source bookkeeping (rule count, last success, last failure, ETag) that the UI reports. It is
 * immutable, so a snapshot build always works from one consistent set of inputs. Persistence lives
 * in `FilterSettingsStore`; this type has no Android or UI dependency.
 *
 * Mode isolation is the central invariant:
 *  * [FilterMode.RELAXED], [FilterMode.STANDARD] and [FilterMode.STRICT] use exactly [profile]'s own
 *    HaGeZi main list. No user-added source and no other selected source contributes.
 *  * [FilterMode.CUSTOM] uses the sources in [selectedSources], plus any legacy user subscription
 *    kept from an older build. At most one HaGeZi Multi tier may be selected at a time.
 *
 * [selectedSources] is retained while a preset mode is active: switching to a preset must not erase
 * the user's custom selection or any downloaded content. It simply has no effect until CUSTOM is
 * chosen again.
 */
data class FilterSettings(
    val profile: FilterProfile = FilterProfile.DEFAULT,
    val mode: FilterMode = FilterMode.DEFAULT,
    /**
     * The source selection used by [FilterMode.CUSTOM]. Kept across preset modes on purpose.
     *
     * The three HaGeZi Multi tiers are mutually exclusive; every other [FilterSource] may be combined.
     * At least one id is kept whenever the settings are saved so a custom mode always has a source to
     * download and display.
     */
    val selectedSources: Set<String> = setOf(FilterSource.HAGEZI_NORMAL.id),
    /**
     * Whether the local filter is *configured*, i.e. whether [FilterAssembler] may publish rules.
     *
     * It is no longer a choice the user can reach: the 關閉 strength option is gone, so a stored
     * `false` (what that option used to persist) is migrated to `true` by [FilterSettingsStore.load]
     * and the home screen's protection switch is the single on/off. What keeps a fresh install from
     * doing anything is that it has no downloaded list, not this flag.
     */
    val enabled: Boolean = true,
    val subscriptions: List<FilterSubscription> = listOf(
        FilterSubscription.forProfile(FilterProfile.DEFAULT)
    ),
    /**
     * Manual allow/block rules from v0.4.1 and earlier.
     *
     * Kept so an upgrade never destroys the user's data, but deliberately **not** published any more:
     * the settings page no longer offers an editor, and hidden rules must not keep changing what is
     * blocked. See [FilterAssembler].
     */
    val manualAllow: List<ManualRule> = emptyList(),
    val manualBlock: List<ManualRule> = emptyList(),
    /** ETag of the stored copy of each subscription, used for conditional updates. */
    val etags: Map<String, String> = emptyMap()
) {

    /** The main list of [profile], when one is present. */
    val builtInSubscription: FilterSubscription?
        get() = subscriptions.firstOrNull { it.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID }

    /** Sources the user added in an older build. The built-in main list is never part of this list. */
    val customSubscriptions: List<FilterSubscription>
        get() = subscriptions.filter { it.origin == SubscriptionOrigin.USER }

    /** The HaGeZi Multi tier selected in custom mode, if one is selected. */
    val selectedMultiTier: FilterProfile?
        get() = FilterSource.entries
            .firstOrNull { it.isMultiTier && it.id in selectedSources }
            ?.profile

    fun etagFor(subscriptionId: String): String? = etags[subscriptionId]

    /**
     * The sources that may contribute to the snapshot *now*.
     *
     * Preset modes answer with the active tier's main list only. Custom mode answers with the selected
     * preset sources plus legacy user sources; whether a source actually has usable content is still
     * decided by [contributesToSnapshot].
     */
    val effectiveSubscriptions: List<FilterSubscription>
        get() = subscriptions.filter(::isActiveSource)

    /**
     * Switches to one of the three preset strengths.
     *
     * When the stored main list already *is* the requested tier, its bookkeeping and ETag are kept:
     * the copy on disk belongs to that list, so the new mode can be published from it before any
     * network work, and the follow-up check is a conditional request. A genuinely different tier
     * starts over — the stored copy belongs to the previous list, so it is not marked ready and its
     * ETag is dropped. User subscriptions and other downloaded preset sources are kept untouched, and
     * [selectedSources] is left alone so a later switch back to CUSTOM keeps the user's selection.
     */
    fun withProfile(profile: FilterProfile): FilterSettings {
        val others = subscriptions.filterNot { it.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID }
        val selection = sanitiseSelection(selectedSources).ifEmpty {
            setOf(FilterSource.forProfile(profile).id)
        }
        val existingMain = builtInSubscription
        val main = existingMain?.takeIf {
            it.origin == SubscriptionOrigin.BUILT_IN &&
                (it.url == profile.primaryUrl || it.url == profile.fallbackUrl)
        } ?: FilterSubscription.forProfile(profile)
        return copy(
            profile = profile,
            mode = FilterMode.forProfile(profile),
            selectedSources = selection,
            enabled = true,
            subscriptions = listOf(main) + others,
            etags = if (main === existingMain) {
                etags
            } else {
                etags - FilterSubscription.PROFILE_SUBSCRIPTION_ID
            }
        )
    }

    /** Switches to [mode]; [FilterMode.CUSTOM] keeps the current custom selection. */
    fun withMode(mode: FilterMode): FilterSettings = when (mode) {
        FilterMode.CUSTOM -> withCustomMode()
        else -> withProfile(mode.profile ?: profile)
    }

    /**
     * Enters custom mode without changing the selected sources.
     *
     * If nothing is selected yet, the active tier becomes the first selection. The main-list record is
     * reused when it already describes that tier, so entering custom mode never re-downloads a list
     * that is already stored.
     */
    fun withCustomMode(): FilterSettings {
        val mainProfile = selectedMultiTier ?: profile
        val selection = normalisedSelection()
        return withCustomSelection(selection, mainProfile)
    }

    /**
     * Turns one source on or off in custom mode.
     *
     * The last remaining source cannot be turned off, so custom mode always has at least one source to
     * show and download. Selecting a HaGeZi Multi tier replaces whichever tier was selected before;
     * all other sources are independent and may be combined.
     */
    fun withSourceSelected(source: FilterSource, selected: Boolean): FilterSettings {
        val current = normalisedSelection()
        if (!selected) {
            val next = current - source.id
            if (next.isEmpty()) return this
            val nextProfile = next.firstNotNullOfOrNull { FilterSource.fromId(it)?.profile } ?: profile
            return withCustomSelection(next, nextProfile)
        }

        val next = if (source.isMultiTier) {
            (current.filterNot { FilterSource.fromId(it)?.isMultiTier == true } + source.id).toSet()
        } else {
            current + source.id
        }
        return withCustomSelection(next, source.profile ?: profile)
    }

    /**
     * The assembler's publish gate, kept as the safety net it always was: `false` publishes no rules
     * while keeping the mode, selection and stored content.
     */
    fun withEnabled(enabled: Boolean): FilterSettings = copy(enabled = enabled)

    /**
     * Adds [subscription], or replaces the entry with the same id.
     *
     * The main-list entry is fully defined by the active tier (id, name, URL and origin come from
     * [profile]); only its enabled flag, state and bookkeeping are taken from [subscription]. Use
     * [withProfile] or [withSourceSelected] to change which list is active.
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

    /** Removes every manual allow entry for [domain]; retained for data compatibility only. */
    fun removeManualAllow(domain: String): FilterSettings =
        copy(manualAllow = manualAllow.filterNot { sameDomain(it.domain, domain) })

    fun removeManualBlock(domain: String): FilterSettings =
        copy(manualBlock = manualBlock.filterNot { sameDomain(it.domain, domain) })

    /**
     * True when [subscription]'s stored rules belong in the published snapshot.
     *
     * Already-downloaded rules stay in use after a failed update: a source in
     * [SubscriptionState.FAILED] that still has a stored rule count keeps contributing, because
     * dropping a working list would remove protection the user already has. The mode still has to
     * select the source first — see [isActiveSource].
     */
    fun contributesToSnapshot(subscription: FilterSubscription): Boolean =
        subscription.enabled && subscription.ruleCount > 0 &&
            (subscription.state == SubscriptionState.READY ||
                subscription.state == SubscriptionState.FAILED)

    /**
     * True when [subscription] is the main list of the active tier.
     *
     * Only the profile's own published locations count, so a stored copy from another tier (or with a
     * different origin) can never be added to the active list. This is also what keeps a preset mode
     * isolated from every other source.
     */
    fun isProfileList(subscription: FilterSubscription): Boolean =
        subscription.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID &&
            subscription.origin == SubscriptionOrigin.BUILT_IN &&
            (subscription.url == profile.primaryUrl || subscription.url == profile.fallbackUrl)

    /**
     * Whether [subscription] is selected by the current mode.
     *
     * A preset mode selects exactly its own main list. Custom mode selects the sources the user ticked
     * and, for upgrade compatibility, any user-added subscription kept from v0.4.1 or earlier.
     */
    fun isActiveSource(subscription: FilterSubscription): Boolean = when (mode) {
        FilterMode.CUSTOM -> when (subscription.origin) {
            SubscriptionOrigin.USER -> true
            SubscriptionOrigin.BUILT_IN -> {
                val source = FilterSource.forSubscription(subscription)
                source != null && source.id in selectedSources
            }
        }

        else -> isProfileList(subscription)
    }

    /** The sources the custom selection points at, in the order the settings page lists them. */
    fun selectedSourceDefinitions(): List<FilterSource> =
        FilterSource.entries.filter { it.id in selectedSources }

    private fun withCustomSelection(
        selection: Set<String>,
        profileForMain: FilterProfile
    ): FilterSettings {
        val sanitised = sanitiseSelection(selection)
        val effectiveSelection = sanitised.ifEmpty {
            setOf(FilterSource.forProfile(profileForMain).id)
        }
        val mainProfile = FilterSource.entries
            .firstOrNull { it.isMultiTier && it.id in effectiveSelection }
            ?.profile
            ?: profileForMain

        val existingMain = builtInSubscription
        val main = existingMain?.takeIf {
            it.origin == SubscriptionOrigin.BUILT_IN &&
                (it.url == mainProfile.primaryUrl || it.url == mainProfile.fallbackUrl)
        } ?: FilterSubscription.forProfile(mainProfile)
        val mainMatches = main === existingMain
        val others = subscriptions.filterNot { it.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID }
        val ensured = ensureSelectedRecords(listOf(main) + others, effectiveSelection)

        return copy(
            profile = mainProfile,
            mode = FilterMode.CUSTOM,
            selectedSources = effectiveSelection,
            enabled = true,
            subscriptions = ensured.distinctBy { it.id },
            etags = if (mainMatches) etags else etags - FilterSubscription.PROFILE_SUBSCRIPTION_ID
        )
    }

    private fun normalisedSelection(): Set<String> {
        val sanitised = sanitiseSelection(selectedSources)
        return sanitised.ifEmpty { setOf(FilterSource.forProfile(profile).id) }
    }

    /** Keeps known ids, and at most one HaGeZi Multi tier. */
    private fun sanitiseSelection(ids: Set<String>): Set<String> {
        val known = FilterSource.entries.filter { it.id in ids }
        val multi = known.firstOrNull { it.isMultiTier }
        return known
            .filter { !it.isMultiTier || it == multi }
            .map { it.id }
            .toSet()
    }

    private fun ensureSelectedRecords(
        base: List<FilterSubscription>,
        selection: Set<String>
    ): List<FilterSubscription> {
        val result = ArrayList(base)
        for (source in FilterSource.entries) {
            if (source.id !in selection) continue
            val index = result.indexOfFirst { it.id == source.subscriptionId }
            when {
                index < 0 -> result.add(source.subscription())
                !source.matches(result[index]) -> result[index] = source.subscription()
            }
        }
        return result
    }

    private fun sameDomain(a: String, b: String): Boolean =
        (DomainName.normalise(a) ?: a.trim().lowercase()) ==
            (DomainName.normalise(b) ?: b.trim().lowercase())

    /** Keeps the first entry per host and match scope; the engine compares canonical names anyway. */
    private fun distinct(rules: List<ManualRule>): List<ManualRule> =
        rules.distinctBy { (DomainName.normalise(it.domain) ?: it.domain.trim().lowercase()) to it.scope }

    companion object {
        /** Profile STANDARD, filter configured, no manual rules, one built-in subscription, nothing downloaded. */
        val DEFAULTS = FilterSettings()

        /**
         * A specific preset tier, with the filter configured and only that tier selected.
         *
         * Always use this (or [withProfile]) rather than `FilterSettings(profile = …)`, which would
         * keep the default tier's main list instead of the requested one.
         */
        fun forProfile(profile: FilterProfile): FilterSettings = FilterSettings(
            profile = profile,
            mode = FilterMode.forProfile(profile),
            selectedSources = setOf(FilterSource.forProfile(profile).id),
            enabled = true,
            subscriptions = listOf(FilterSubscription.forProfile(profile))
        )
    }
}
