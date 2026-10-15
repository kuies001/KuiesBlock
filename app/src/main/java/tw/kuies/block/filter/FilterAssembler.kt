package tw.kuies.block.filter

/**
 * Builds the published snapshot from the stored settings and subscription contents.
 *
 * Call it after any change to the settings or to a subscription; it publishes through the existing
 * [FilterRepository], which swaps the snapshot atomically, so a query always reads either the previous
 * rule set or the new one.
 *
 * What it decides:
 *  * Only subscriptions that may contribute are read ([FilterSettings.contributesToSnapshot]).
 *  * The main list is the active tier's own list and only that one — the three tiers are never
 *    combined, because they overlap and stacking them would make the effective strength impossible to
 *    describe honestly ([FilterSettings.isProfileList]).
 *  * A source whose stored text is missing, empty or longer than [maxRules] contributes nothing rather
 *    than partial rules.
 *
 * Manual rules are passed through as they are; the snapshot canonicalises them and drops anything that
 * is not a domain.
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
        val blocked = ArrayList<String>(1024)
        val allowed = ArrayList<String>(64)

        for (subscription in settings.subscriptions) {
            if (!settings.contributesToSnapshot(subscription)) continue
            if (subscription.origin == SubscriptionOrigin.BUILT_IN &&
                !settings.isProfileList(subscription)
            ) {
                continue
            }

            val text = contentStore.read(subscription.id) ?: continue
            val parsed = DnsRuleParser.parse(text, maxRules)
            if (parsed.stats.truncated) continue
            if (parsed.blocked.isEmpty && parsed.allowed.isEmpty) continue

            parsed.blocked.forEach(blocked::add)
            parsed.allowed.forEach(allowed::add)
        }

        return repository.publish(
            manualAllow = settings.manualAllow,
            manualBlock = settings.manualBlock,
            subscriptionBlock = DomainSet.ofNormalised(blocked),
            subscriptionAllow = DomainSet.ofNormalised(allowed)
        )
    }
}
