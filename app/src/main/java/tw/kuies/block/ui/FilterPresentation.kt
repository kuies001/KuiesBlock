package tw.kuies.block.ui

import java.util.Locale
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSource
import tw.kuies.block.filter.FilterSourceStatus
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.FilterUpdateRecord
import tw.kuies.block.filter.LocalFilterPolicy
import tw.kuies.block.filter.LocalFilterState
import tw.kuies.block.filter.SubscriptionState
import tw.kuies.block.filter.UpdateCheckOutcome

/**
 * How the interception strength is worded on screen.
 *
 * Pure and free of Compose and of resources, exactly like [ProtectionPresentation], so the wording
 * that decides what the user is told can be unit tested. It never claims more than the status it was
 * given: "生效中" is only reachable when rules are really published *and* the tunnel is running *and*
 * the system is still handing its queries to the tunnel.
 */
object FilterPresentation {

    /**
     * The four mode choices as the user sees them.
     *
     * The three presets keep their v0.4.1 wording; 自訂 is the new fourth choice and is the only one
     * that exposes a source picker.
     */
    fun modeLabel(mode: FilterMode): String = when (mode) {
        FilterMode.RELAXED -> "寬鬆"
        FilterMode.STANDARD -> "標準（推薦）"
        FilterMode.STRICT -> "嚴格"
        FilterMode.CUSTOM -> "自訂"
    }

    /** The concise value shown on a collapsed strength section; the radio keeps the 推薦 hint. */
    fun modeSummaryLabel(mode: FilterMode): String = when (mode) {
        FilterMode.RELAXED -> "寬鬆"
        FilterMode.STANDARD -> "標準"
        FilterMode.STRICT -> "嚴格"
        FilterMode.CUSTOM -> "自訂"
    }

    /** A one-line description of what the chosen mode does, so the trade-offs are visible up front. */
    fun modeDetail(mode: FilterMode): String = when (mode) {
        FilterMode.RELAXED -> "固定使用 HaGeZi Multi LIGHT，誤擋機會最低。"
        FilterMode.STANDARD -> "固定使用 HaGeZi Multi NORMAL，日常使用的建議選項。"
        FilterMode.STRICT -> "固定使用 HaGeZi Multi PRO++，攔截最積極。"
        FilterMode.CUSTOM -> "自行勾選要啟用的過濾來源；HaGeZi Multi 主清單只能擇一。"
    }

    /**
     * The strength as the user sees it.
     *
     * Only the three preset tiers exist as [FilterProfile] values: [FilterMode.CUSTOM] is a mode, not a
     * fourth list, and is labelled by [modeLabel].
     */
    fun strengthLabel(profile: FilterProfile): String = modeLabel(FilterMode.forProfile(profile))

    /** A one-line description of what the chosen tier does. */
    fun strengthDetail(profile: FilterProfile): String = modeDetail(FilterMode.forProfile(profile))

    /**
     * The effective state, in the order the user needs it: a download in progress → whether
     * interception is active at all → paused by Private DNS → not ready → in effect.
     *
     * [busy] is a download in progress, which is reported before anything else because it explains
     * why the numbers have not moved yet.
     *
     * [protectionState] is the real state the service reported, not a yes/no. Four of its cases must
     * read differently, because they mean different things to the user:
     *  * not running at all (`NOT_AUTHORIZED`, `AUTHORIZED_IDLE`) — the local filter is always
     *    *configured* now (the 關閉 option is gone), so stored rules say nothing about whether any
     *    query reaches them; with protection off no query ever does;
     *  * `STARTING` — the tunnel is still coming up, so it is neither idle nor working yet;
     *  * `ACTIVE` with the system still handing its queries over — the one case that may read as
     *    「生效中」;
     *  * `ERROR` — the app's own error wording, so a failure is never presented as a state the user
     *    can just wait out.
     *
     * [tw.kuies.block.core.ProtectionStateRules.isInterceptionActive] answers the "is interception
     * actually active" question, so the settings page and the home screen cannot disagree about it,
     * and the whole thing stays fail-closed: only `ACTIVE` may reach the in-effect wording.
     */
    fun stateLabel(
        status: FilterStatus,
        privateDnsMode: PrivateDnsMode,
        protectionState: ProtectionState,
        isServiceRunning: Boolean = false,
        busy: Boolean = false
    ): String {
        if (busy) return "正在下載清單…"

        if (!ProtectionStateRules.isInterceptionActive(protectionState)) {
            return when (protectionState) {
                ProtectionState.STARTING -> "正在啟動防護…"
                ProtectionState.ERROR ->
                    ProtectionPresentation.title(protectionState, isServiceRunning)
                // Nothing is running here, and it is not a failure: a fresh install and a tunnel the
                // user switched off are the same thing to this line.
                ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE -> "防護未啟動"
                // Unreachable: isInterceptionActive() is true exactly for ACTIVE. Kept fail-closed so
                // a future state can never inherit the 「生效中」 wording by falling into the branch
                // below.
                ProtectionState.ACTIVE -> "防護未啟動"
            }
        }

        return when (LocalFilterPolicy.state(privateDnsMode, status.effectiveRuleCount)) {
            // Strict Private DNS is decided first on purpose: rules may be loaded and still do nothing,
            // so this must never read as "生效中".
            LocalFilterState.PAUSED_PRIVATE_DNS ->
                "本機規則已暫停：系統私人 DNS 為指定主機名稱，查詢不會經過本機。"

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

    /**
     * The last *automatic* check, which is a different question from the last successful update above.
     *
     * A check that found nothing downloaded says so instead of implying success, and a failure says the
     * previous lists are still in use — the same promise the updater really keeps.
     */
    fun lastCheckLabel(record: FilterUpdateRecord, now: Long): String {
        val at = record.checkedAtMillis
        // The time is omitted rather than guessed when it was never recorded.
        val whenClause = if (at == null) "" else "${lastSuccessLabel(at, now)}，"
        return when (val outcome = record.outcome) {
            UpdateCheckOutcome.Never -> "尚未自動檢查。"
            UpdateCheckOutcome.NothingToCheck -> "上次檢查：沒有已下載的清單可更新。"
            UpdateCheckOutcome.AllCurrent -> "上次檢查：${whenClause}全部為最新。"
            is UpdateCheckOutcome.Updated -> "上次檢查：${whenClause}已更新 ${outcome.count} 項。"
            is UpdateCheckOutcome.Failed ->
                "上次檢查：${whenClause}${outcome.count} 項失敗；仍使用上一次成功的清單。"
        }
    }

    /** The licence, and the fact that follows from it: the content is fetched on the device. */
    const val LICENCE = "清單授權：GNU GPL-3.0（App 不內建清單內容，於裝置端下載）"

    fun sourceEnabledLabel(enabled: Boolean): String = if (enabled) "已啟用" else "已停用"

    /**
     * How many rules the engine can match right now.
     *
     * Reports the published snapshot, never what a list merely contains: a source whose rules are
     * not in effect must not read as though they were.
     */
    fun effectiveRuleCountLabel(status: FilterStatus): String =
        if (status.effectiveRuleCount > 0) {
            "${"%,d".format(Locale.US, status.effectiveRuleCount)} 條"
        } else {
            "尚未生效"
        }

    /**
     * When the active list last updated successfully, relative to [now].
     *
     * Relative on purpose: an absolute timestamp would need a locale and a time zone, while what the
     * user needs to know is only whether the list is current.
     */
    fun lastSuccessLabel(lastSuccessAtMillis: Long?, now: Long): String {
        val at = lastSuccessAtMillis ?: return "從未成功更新"
        val elapsed = now - at
        if (elapsed < 0) return "剛剛"
        val minutes = elapsed / 60_000
        return when {
            minutes < 1 -> "剛剛"
            minutes < 60 -> "$minutes 分鐘前"
            minutes < 60 * 24 -> "${minutes / 60} 小時前"
            else -> "${minutes / (60 * 24)} 天前"
        }
    }

    /**
     * The last successful update across the sources in effect.
     *
     * Only [FilterSourceStatus.lastSuccessAtMillis] counts, and the updater writes that after a real
     * success — so a failed attempt can never make this line look freshly updated. A downloaded list
     * whose time was never recorded says so rather than guessing.
     */
    fun lastUpdateLabel(status: FilterStatus, now: Long): String {
        val sources = status.sourceStatuses.filter { it.enabled }
        if (sources.isEmpty()) return "目前沒有啟用的過濾來源。"
        val latest = sources.mapNotNull { it.lastSuccessAtMillis }.maxOrNull()
        if (latest != null) return lastSuccessLabel(latest, now)
        return if (sources.any { it.ruleCount > 0 }) "尚未記錄更新時間" else "尚未下載"
    }

    /**
     * A short problem line for one source, or null when there is nothing wrong to report.
     *
     * A failed update keeps the previous rules in use, so the line says that instead of implying the
     * source is gone; 尚未下載 and 沒有可用規則 keep an empty source from looking ready.
     */
    fun sourceIssueLabel(source: FilterSourceStatus): String? = when (source.state) {
        SubscriptionState.NOT_READY -> "尚未下載"
        SubscriptionState.DISABLED -> null
        SubscriptionState.READY -> if (source.ruleCount <= 0) "沒有可用規則" else null
        SubscriptionState.FAILED ->
            "更新失敗" +
                (source.lastError?.let { "（$it）" } ?: "") +
                "；仍使用上次成功的清單"
    }

    /** Whether [sourceIssueLabel] describes a failure rather than a not-yet-downloaded source. */
    fun sourceIssueIsError(source: FilterSourceStatus): Boolean =
        source.state == SubscriptionState.FAILED

    /** The maintainers whose lists are in effect, for the compact attribution line. */
    fun sourceAttributionLabel(status: FilterStatus): String? {
        val maintainers = status.sourceStatuses
            .filter { it.enabled }
            .mapNotNull { source -> FilterSource.fromId(source.id)?.maintainer ?: source.maintainer }
            .distinct()
        return maintainers.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "來源：")
    }
}
