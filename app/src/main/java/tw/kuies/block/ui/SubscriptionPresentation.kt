package tw.kuies.block.ui

import tw.kuies.block.filter.BuiltInSubscriptionHosts
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionDraft
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

/**
 * How a custom subscription source is worded on the settings screen.
 *
 * Pure and free of Compose and of resources, exactly like [FilterPresentation], so the wording that
 * decides what the user is told can be unit tested. It deliberately does not restate the wording
 * [FilterPresentation] already owns: the enabled/disabled pair and the relative last-success time are
 * delegated to it, so the two sections of the screen cannot drift apart.
 *
 * Two different claims are kept apart on purpose, because a source that downloaded fine is not
 * necessarily a source that is filtering:
 *  * [updateResultLabel] — what the **last attempt** did (never downloaded / succeeded / failed).
 *  * [rulesInEffectLabel] — whether the rules **are in use right now**. A failed update that kept the
 *    previous copy still filters, so it says the rules are in effect while the result line says the
 *    attempt failed. Nothing here ever claims the filter as a whole is working; that is
 *    [FilterPresentation.stateLabel]'s job, and it is the only thing that knows the master switch and
 *    the system Private DNS mode.
 */
object SubscriptionPresentation {

    /** The section's heading and the one-line explanation under it. */
    const val SECTION_TITLE = "自訂訂閱來源"

    /**
     * Not a `const`: the limit it names is [SubscriptionDraft.MAX_SUBSCRIPTIONS], so the sentence the
     * user reads cannot say a different number from the one the draft enforces.
     */
    val SECTION_BODY =
        "可加入最多 ${SubscriptionDraft.MAX_SUBSCRIPTIONS} 個公開的 https 清單網址；" +
            "清單由裝置自行下載，App 不內建內容。"

    const val ADD_URL_LABEL = "訂閱網址（https://…）"
    const val ADD_NAME_LABEL = "顯示名稱（可留空，將使用網域名稱）"
    const val ADD_ACTION = "新增來源"

    const val FIELD_ENABLED = "狀態"
    const val FIELD_RULES = "規則數"
    const val FIELD_LAST_UPDATE = "最近成功更新"
    const val FIELD_RESULT = "最近一次結果"

    const val ENABLE_ACTION = "啟用"
    const val DISABLE_ACTION = "停用"
    const val UPDATE_ACTION = "立即更新"
    const val UPDATING_ACTION = "更新中…"
    const val DELETE_ACTION = "刪除"

    const val EMPTY_STATE = "尚未新增自訂來源。"

    /** The row's name: the stored display name, which the draft always fills with something usable. */
    fun sourceName(subscription: FilterSubscription): String = subscription.displayName

    /**
     * The row's second line: the host the list is fetched from, falling back to the whole URL when the
     * stored value is not a URL this build can read.
     *
     * The host is what identifies a source at a glance; the full URL is only shown when there is no
     * host to show. Nothing here decodes or resolves anything.
     */
    fun sourceHost(subscription: FilterSubscription): String =
        BuiltInSubscriptionHosts.hostOf(subscription.url) ?: subscription.url

    /** Reuses [FilterPresentation]'s wording so both lists on the screen read the same. */
    fun enabledLabel(enabled: Boolean): String = FilterPresentation.sourceEnabledLabel(enabled)

    /** How many custom sources are configured and how many are allowed. */
    fun sourceCountLabel(userSourceCount: Int): String =
        "自訂來源：$userSourceCount／${SubscriptionDraft.MAX_SUBSCRIPTIONS}"

    /**
     * What is stored for this source.
     *
     * A count of zero is not the same as "not downloaded": say which one it is.
     */
    fun ruleCountLabel(subscription: FilterSubscription): String = when {
        subscription.ruleCount > 0 -> "${subscription.ruleCount} 條"
        subscription.state == SubscriptionState.READY -> "清單沒有可用規則"
        else -> "尚未下載"
    }

    /** When this source last succeeded, relative to [now]; the wording is [FilterPresentation]'s. */
    fun lastUpdateLabel(subscription: FilterSubscription, now: Long): String =
        FilterPresentation.lastSuccessLabel(subscription.lastSuccessAtMillis, now)

    /**
     * The outcome of the most recent attempt for this source.
     *
     * A failure always says the previous list is still in use, because that is what the updater
     * guarantees: a user must never read a failed update as "this source stopped protecting me".
     */
    fun updateResultLabel(subscription: FilterSubscription, now: Long): String =
        when (subscription.state) {
            SubscriptionState.NOT_READY -> "尚未下載清單。"
            SubscriptionState.DISABLED -> "此來源已停用。"
            // Ready without a timestamp is still a success, and never the contradictory
            // 「從未成功更新」.
            SubscriptionState.READY -> subscription.lastSuccessAtMillis
                ?.let { "上次更新成功（${FilterPresentation.lastSuccessLabel(it, now)}）。" }
                ?: "上次更新成功。"

            SubscriptionState.FAILED ->
                "上次更新失敗" +
                    (subscription.lastError?.let { "（$it）" } ?: "") +
                    "；仍使用上一次成功的清單。"
        }

    /**
     * Whether this source's stored rules are part of what the engine matches against.
     *
     * Delegated to [FilterSettings.contributesToSnapshot], the predicate the assembler itself uses, so
     * this screen can never disagree with what the engine actually publishes. That method reads only
     * its argument, so calling it on the default settings is the way to reuse the rule without
     * duplicating it.
     */
    fun rulesInEffect(subscription: FilterSubscription): Boolean =
        FilterSettings.DEFAULTS.contributesToSnapshot(subscription)

    /**
     * Whether this source's rules are in use, and when they are not, why.
     *
     * This is the line that must never be folded into [updateResultLabel]: a source whose last update
     * failed can still be filtering with its stored copy, and a source that downloaded perfectly can
     * be contributing nothing because the user turned it off.
     */
    fun rulesInEffectLabel(subscription: FilterSubscription): String = when {
        !subscription.enabled -> "已停用，規則未納入攔截。"
        !rulesInEffect(subscription) -> "尚未下載到可用的規則，未納入攔截。"
        subscription.state == SubscriptionState.FAILED ->
            "仍以已下載的 ${subscription.ruleCount} 條規則納入攔截。"
        else -> "以已下載的 ${subscription.ruleCount} 條規則納入攔截。"
    }

    /** The action offered for a row's switch: the opposite of its current state. */
    fun toggleActionLabel(enabled: Boolean): String = if (enabled) DISABLE_ACTION else ENABLE_ACTION

    /** The update button's label, so a running download cannot look like an idle one. */
    fun updateActionLabel(busy: Boolean): String = if (busy) UPDATING_ACTION else UPDATE_ACTION

    /** How a source added by hand differs from the list the app ships with. */
    fun originLabel(origin: SubscriptionOrigin): String = when (origin) {
        SubscriptionOrigin.BUILT_IN -> "內建"
        SubscriptionOrigin.USER -> "自訂"
    }

    /**
     * A friendly sentence for a refused draft.
     *
     * Every message says what to do next, and none of them suggests weakening the connection: a
     * plain-http source is refused, never offered a way to make it work.
     */
    fun rejectionLabel(reason: SubscriptionDraft.Rejection): String = when (reason) {
        SubscriptionDraft.Rejection.EMPTY_URL -> "請先輸入訂閱網址。"
        SubscriptionDraft.Rejection.NOT_HTTPS -> "只接受 https:// 開頭的網址，其他連線方式不安全。"
        SubscriptionDraft.Rejection.URL_TOO_LONG ->
            "網址太長，最多 ${SubscriptionDraft.MAX_URL_LENGTH} 個字元。"

        SubscriptionDraft.Rejection.BAD_HOST ->
            "這個網址的主機名稱無法使用（不可指向本機或私有網路）。"

        SubscriptionDraft.Rejection.DUPLICATE -> "這個網址已經在清單裡了。"
        SubscriptionDraft.Rejection.TOO_MANY ->
            "最多只能有 ${SubscriptionDraft.MAX_SUBSCRIPTIONS} 個自訂來源，請先刪除不需要的。"
    }
}
