package tw.kuies.block.filter

/**
 * Builds the published snapshot from the stored settings and subscription contents.
 *
 * Call it after any change to the settings or to a subscription; it publishes through the existing
 * [FilterRepository], which swaps the snapshot atomically, so a query always reads either the previous
 * rule set or the new one.
 *
 * What it decides:
 *  * Only the sources the active mode selects are read ([FilterSettings.effectiveSubscriptions]).
 *    A preset mode therefore publishes its own HaGeZi tier and nothing else, even if a legacy user
 *    subscription or another downloaded preset source is still stored.
 *  * Only sources that may contribute are read ([FilterSettings.contributesToSnapshot]).
 *  * A source whose stored text is missing, empty or longer than [maxRules] contributes nothing rather
 *    than partial rules.
 *  * Manual allow/block rules stored by v0.4.1 and earlier are **not** published. The settings page no
 *    longer offers an editor, so keeping them active would be a hidden rule set the user cannot see
 *    or change. The stored lists stay in the preferences file for a future migration.
 *
 * Parsing happens here, off the packet path. Two subscriptions' rule sets are merged by union, so this
 * cost is paid once per publish, not per query.
 */
class FilterAssembler(
    private val repository: FilterRepository,
    private val contentStore: SubscriptionContentStore,
    private val maxRules: Int = DnsRuleParser.MAX_RULES
) {

    fun assemble(settings: FilterSettings): FilterSnapshot {
        // The master switch is checked once, here, and it is the *only* place it matters: with the
        // filter off nothing is published at all, so the packet path falls through to the DoH
        // transport exactly as it did before this feature existed.
        if (!settings.enabled) return repository.clear()

        val blocked = ArrayList<String>(1024)
        val allowed = ArrayList<String>(64)

        for (subscription in settings.effectiveSubscriptions) {
            if (!settings.contributesToSnapshot(subscription)) continue

            val text = contentStore.read(subscription.id) ?: continue
            val parsed = DnsRuleParser.parse(text, maxRules)
            if (parsed.stats.truncated) continue
            if (parsed.blocked.isEmpty && parsed.allowed.isEmpty) continue

            parsed.blocked.forEach(blocked::add)
            parsed.allowed.forEach(allowed::add)
        }

        return repository.publish(
            // Deliberately empty: the manual-rule editor was removed from the settings page. The data
            // stays in FilterSettings for compatibility, but it must not keep filtering invisibly.
            manualAllow = emptyList(),
            manualBlock = emptyList(),
            subscriptionBlock = DomainSet.ofNormalised(blocked),
            subscriptionAllow = DomainSet.ofNormalised(allowed)
        )
    }
}
