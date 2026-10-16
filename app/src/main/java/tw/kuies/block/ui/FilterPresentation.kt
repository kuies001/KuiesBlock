package tw.kuies.block.ui

import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.LocalFilterPolicy
import tw.kuies.block.filter.LocalFilterState
import tw.kuies.block.filter.SubscriptionState

/**
 * How the interception strength is worded on screen.
 *
 * Pure and free of Compose and of resources, exactly like [ProtectionPresentation], so the wording
 * that decides what the user is told can be unit tested. It never claims more than the status it was
 * given: "生效中" is only reachable when rules are really published *and* the system is still handing
 * its queries to the tunnel.
 */
object FilterPresentation {

    /**
     * The strength as the user sees it. `null` is the filter switched off — a real choice, not an
     * error state.
     */
    fun strengthLabel(profile: FilterProfile?): String = when (profile) {
        null -> "關閉"
        FilterProfile.RELAXED -> "寬鬆"
        FilterProfile.STANDARD -> "標準（推薦）"
        FilterProfile.STRICT -> "嚴格"
    }

    /** A one-line description of what the chosen tier does, so 嚴格's aggressiveness is not a surprise. */
    fun strengthDetail(profile: FilterProfile?): String = when (profile) {
        null -> "目前不會套用任何本機規則。"
        FilterProfile.RELAXED -> "只擋最明顯的廣告與追蹤網域，誤擋機會最低。"
        FilterProfile.STANDARD -> "廣泛攔截廣告與追蹤網域，日常使用的建議選項。"
        FilterProfile.STRICT -> "攔截最積極，可能影響部分網站或 App 的功能。"
    }

    /**
     * The effective state, in the order the user needs it:
     * off → paused by Private DNS → not ready → in effect.
     *
     * [busy] is a download in progress, which is reported before anything else because it explains
     * why the numbers have not moved yet.
     */
    fun stateLabel(status: FilterStatus, privateDnsMode: PrivateDnsMode, busy: Boolean = false): String {
        if (busy) return "正在下載清單…"
        if (!status.enabled) return "未啟用"

        return when (LocalFilterPolicy.state(privateDnsMode, status.effectiveRuleCount)) {
            // Strict Private DNS is decided first on purpose: rules may be loaded and still do nothing,
            // so this must never read as "生效中".
            LocalFilterState.PAUSED_PRIVATE_DNS ->
                "已暫停：系統私人 DNS 為指定主機名稱，查詢不會經過本機。"

            LocalFilterState.NO_RULES -> if (profileUpdateFailed(status)) {
                "上次更新失敗，目前沒有可用規則。"
            } else {
                "規則尚未就緒。"
            }

            LocalFilterState.ACTIVE -> "本機規則生效中（${status.effectiveRuleCount} 條）。"
        }
    }

    /** True when the active tier's own list is the one whose last update failed. */
    private fun profileUpdateFailed(status: FilterStatus): Boolean =
        status.subscriptions
            .firstOrNull { it.id == FilterSubscription.PROFILE_SUBSCRIPTION_ID }
            ?.state == SubscriptionState.FAILED
}
