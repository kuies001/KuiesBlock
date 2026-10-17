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
 * One active source as 過濾器管理 lists it.
 *
 * [id] is the [FilterSource] id for a built-in source and the subscription id for a legacy
 * user-added source. [ruleCount] describes the stored copy; the effective total is
 * [FilterStatus.effectiveRuleCount], which comes from the published snapshot.
 */
data class FilterSourceStatus(
    val id: String,
    val subscriptionId: String,
    val displayName: String,
    val maintainer: String?,
    val isBuiltIn: Boolean,
    val enabled: Boolean,
    val state: SubscriptionState,
    val ruleCount: Int,
    val lastSuccessAtMillis: Long?,
    val lastError: String?
)

/**
 * A plain read model for the filter UI: which mode is active, what each active source did last time,
 * and how many rules are actually published right now.
 *
 * Deliberately free of Compose and of any Android type, and deliberately derived from the settings plus
 * the snapshot — never from what a source *hoped* to contribute — so the screen can only claim what is
 * really in effect. A snapshot with no rules at all is reported as such ([hasUsableRules] false); an
 * empty list is never presented as working protection.
 */
data class FilterStatus(
    val profile: FilterProfile,
    val mode: FilterMode,
    val selectedSources: Set<String>,
    val profileListName: String,
    /**
     * Whether the local filter is configured at all ([FilterSettings.enabled]).
     *
     * Always true for settings read from the store: the 關閉 option is gone, so this no longer means
     * "the user switched filtering off". Whether rules can take effect is the protection state's
     * business, not this flag's.
     */
    val enabled: Boolean,
    val subscriptions: List<SubscriptionStatus>,
    /** The sources that may contribute in the active mode, including ones not downloaded yet. */
    val sourceStatuses: List<FilterSourceStatus>,
    /** Rules contributed by subscriptions to the published snapshot. */
    val subscriptionRuleCount: Int,
    /** Rules the user added by hand that are in the published snapshot. Always zero now. */
    val manualRuleCount: Int,
    /** Everything the engine can match against right now. */
    val effectiveRuleCount: Int,
    val hasUsableRules: Boolean,
    val snapshotVersion: Long,
    /**
     * The user's stored manual rules and legacy sources, carried as settings objects for data
     * compatibility. The settings page no longer edits them.
     */
    val manualAllow: List<ManualRule> = emptyList(),
    val manualBlock: List<ManualRule> = emptyList(),
    val customSources: List<FilterSubscription> = emptyList()
) {
    companion object {
        fun of(settings: FilterSettings, snapshot: FilterSnapshot): FilterStatus = FilterStatus(
            profile = settings.profile,
            mode = settings.mode,
            selectedSources = settings.selectedSources,
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
            sourceStatuses = activeSourceStatuses(settings),
            subscriptionRuleCount = snapshot.subscriptionRuleCount,
            manualRuleCount = snapshot.userRuleCount,
            effectiveRuleCount = snapshot.subscriptionRuleCount + snapshot.userRuleCount,
            hasUsableRules = !snapshot.isEmpty,
            snapshotVersion = snapshot.version,
            manualAllow = settings.manualAllow,
            manualBlock = settings.manualBlock,
            customSources = settings.customSubscriptions
        )

        private fun activeSourceStatuses(settings: FilterSettings): List<FilterSourceStatus> {
            if (settings.mode != FilterMode.CUSTOM) {
                val source = FilterSource.forProfile(settings.profile)
                val subscription = settings.subscriptions.firstOrNull {
                    source.matches(it) && settings.isProfileList(it)
                } ?: source.subscription()
                return listOf(statusOf(source, subscription))
            }

            val preset = settings.selectedSourceDefinitions().map { source ->
                val subscription = settings.subscriptions.firstOrNull { source.matches(it) }
                    ?: source.subscription()
                statusOf(source, subscription)
            }
            // Sources added by the user in v0.4.1 or earlier stay effective in custom mode so an
            // upgrade keeps the blocking combination the user already had. They are no longer edited
            // on this page, but they are still named here rather than hidden.
            val legacy = settings.subscriptions
                .filter { it.origin == SubscriptionOrigin.USER && it.enabled }
                .map { subscription ->
                    FilterSourceStatus(
                        id = subscription.id,
                        subscriptionId = subscription.id,
                        displayName = subscription.displayName,
                        maintainer = null,
                        isBuiltIn = false,
                        enabled = subscription.enabled,
                        state = subscription.state,
                        ruleCount = subscription.ruleCount,
                        lastSuccessAtMillis = subscription.lastSuccessAtMillis,
                        lastError = subscription.lastError
                    )
                }
            return preset + legacy
        }

        private fun statusOf(
            source: FilterSource,
            subscription: FilterSubscription
        ): FilterSourceStatus = FilterSourceStatus(
            id = source.id,
            subscriptionId = subscription.id,
            displayName = source.displayName,
            maintainer = source.maintainer,
            isBuiltIn = true,
            enabled = subscription.enabled,
            state = subscription.state,
            ruleCount = subscription.ruleCount,
            lastSuccessAtMillis = subscription.lastSuccessAtMillis,
            lastError = subscription.lastError
        )
    }
}
