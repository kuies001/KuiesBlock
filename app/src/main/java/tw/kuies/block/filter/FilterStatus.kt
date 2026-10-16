package tw.kuies.block.filter

/** One subscription as the UI shows it. */
data class SubscriptionStatus(
    val id: String,
    val displayName: String,
    val origin: SubscriptionOrigin,
    val enabled: Boolean,
    val state: SubscriptionState,
    val ruleCount: Int,
    val lastSuccessAtMillis: Long?,
    val lastError: String?
)

/**
 * A plain read model for the filter UI: what is configured, what each source did last time, and how
 * many rules are actually published right now.
 *
 * Deliberately free of Compose and of any Android type, and deliberately derived from the settings plus
 * the snapshot — never from what a source *hoped* to contribute — so the screen can only claim what is
 * really in effect. A snapshot with no rules at all is reported as such ([hasUsableRules] false); an
 * empty list is never presented as working protection.
 */
data class FilterStatus(
    val profile: FilterProfile,
    val profileListName: String,
    /**
     * Whether the local filter is configured at all ([FilterSettings.enabled]).
     *
     * Always true for settings read from the store: the 關閉 option is gone, so this no longer means
     * "the user switched filtering off". Whether rules can take effect is the protection state's
     * business, not this flag's — [tw.kuies.block.ui.FilterPresentation.stateLabel] reads that
     * separately.
     */
    val enabled: Boolean,
    val subscriptions: List<SubscriptionStatus>,
    /** Rules contributed by subscriptions to the published snapshot. */
    val subscriptionRuleCount: Int,
    /** Rules the user added by hand that are in the published snapshot. */
    val manualRuleCount: Int,
    /** Everything the engine can match against right now. */
    val effectiveRuleCount: Int,
    val hasUsableRules: Boolean,
    val snapshotVersion: Long,
    /**
     * The user's own rules and sources, carried as the settings objects rather than as counts: a
     * screen that offers to remove or update one needs the object to act on, and rebuilding it from a
     * count would be guesswork.
     */
    val manualAllow: List<ManualRule> = emptyList(),
    val manualBlock: List<ManualRule> = emptyList(),
    val customSources: List<FilterSubscription> = emptyList()
) {
    companion object {
        fun of(settings: FilterSettings, snapshot: FilterSnapshot): FilterStatus = FilterStatus(
            profile = settings.profile,
            profileListName = settings.profile.listName,
            enabled = settings.enabled,
            subscriptions = settings.subscriptions.map { subscription ->
                SubscriptionStatus(
                    id = subscription.id,
                    displayName = subscription.displayName,
                    origin = subscription.origin,
                    enabled = subscription.enabled,
                    state = subscription.state,
                    ruleCount = subscription.ruleCount,
                    lastSuccessAtMillis = subscription.lastSuccessAtMillis,
                    lastError = subscription.lastError
                )
            },
            subscriptionRuleCount = snapshot.subscriptionRuleCount,
            manualRuleCount = snapshot.userRuleCount,
            effectiveRuleCount = snapshot.subscriptionRuleCount + snapshot.userRuleCount,
            hasUsableRules = !snapshot.isEmpty,
            snapshotVersion = snapshot.version,
            manualAllow = settings.manualAllow,
            manualBlock = settings.manualBlock,
            customSources = settings.customSubscriptions
        )
    }
}
