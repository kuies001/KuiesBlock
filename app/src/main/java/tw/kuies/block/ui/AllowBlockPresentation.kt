package tw.kuies.block.ui

import tw.kuies.block.filter.ManualRuleDraft
import tw.kuies.block.filter.MatchScope

/**
 * How the manual allow/block list is worded on screen.
 *
 * Pure and free of Compose and of resources, exactly like [FilterPresentation], so the sentence that
 * decides what the user is told can be unit tested. The wording matters here more than anywhere
 * else in the app: an allow rule does **not** make a domain reachable when an upstream resolver
 * still blocks it, and [ALLOW_UPSTREAM_NOTE] is what stops the allow tab from pretending otherwise.
 */
object AllowBlockPresentation {

    /** The section title, as the settings list shows it. */
    const val SECTION_TITLE = "允許與封鎖清單"

    /** Tab 1: domains the local filter must not block. */
    const val TAB_ALLOW = "允許清單"

    /** Tab 2: domains the local filter must block. */
    const val TAB_BLOCK = "封鎖清單"

    /**
     * The one honest sentence about what an allow rule can and cannot do.
     *
     * An allow entry only wins against KuiesBlock's own local rules; the query still goes upstream,
     * so a Pi-hole, a filtering upstream resolver or the network's own DNS can still answer with a
     * block. Saying so is what keeps the feature from reading as a bypass switch.
     */
    const val ALLOW_UPSTREAM_NOTE =
        "允許清單只會略過 KuiesBlock 本機封鎖，上游 DNS 服務仍可能阻擋該網域。"

    /** The empty state of the allow tab. */
    const val ALLOW_EMPTY = "允許清單目前沒有網域。"

    /** The empty state of the block tab. */
    const val BLOCK_EMPTY = "封鎖清單目前沒有網域。"

    /** The label of the field a new domain is typed into. */
    const val DOMAIN_FIELD_LABEL = "新增網域"

    /** An example of the shape that is accepted, so the user does not paste a URL. */
    const val DOMAIN_PLACEHOLDER = "例如 example.com"

    /** The label above the choice of how far the new rule reaches. */
    const val SCOPE_LABEL = "比對範圍"

    /** The scope of a rule that matches this host only. */
    const val SCOPE_EXACT = "僅此網域"

    /** The scope of a rule that matches this host and everything below it. */
    const val SCOPE_SUBDOMAIN = "含所有子網域"

    /** The search box above the list. */
    const val SEARCH_PLACEHOLDER = "搜尋清單中的網域"

    /** Shown when the search matched nothing, which is not the same as an empty list. */
    const val NO_MATCH = "找不到符合的網域。"

    /** The button that adds what is currently typed. */
    const val ADD_LABEL = "新增"

    /** The button that deletes one rule. */
    const val REMOVE_LABEL = "移除"

    /** The two tab labels, by tab. */
    fun tabLabel(blocking: Boolean): String = if (blocking) TAB_BLOCK else TAB_ALLOW

    /** The empty state of the list that is on screen. */
    fun emptyLabel(blocking: Boolean): String = if (blocking) BLOCK_EMPTY else ALLOW_EMPTY

    /**
     * How far one rule reaches, as the row shows it.
     *
     * Each row states its own scope because the two are stored separately and the same domain can
     * legitimately appear in a list twice, once per scope.
     */
    fun scopeLabel(scope: MatchScope): String = when (scope) {
        MatchScope.EXACT -> SCOPE_EXACT
        MatchScope.SUBDOMAIN -> SCOPE_SUBDOMAIN
    }

    /**
     * What to tell the user when a draft is refused.
     *
     * Every [ManualRuleDraft.Rejection] has its own sentence, and each one names the fix rather than
     * the internal reason: a rejection is always something the user can retype, never a silent drop.
     */
    fun rejectionMessage(reason: ManualRuleDraft.Rejection): String = when (reason) {
        ManualRuleDraft.Rejection.EMPTY -> "請先輸入網域。"
        ManualRuleDraft.Rejection.TOO_LONG ->
            "網域太長，最多 ${ManualRuleDraft.MAX_DOMAIN_LENGTH} 個字元。"
        ManualRuleDraft.Rejection.NOT_A_DOMAIN -> "這不是有效的網域，請檢查拼字。"
        ManualRuleDraft.Rejection.IP_ADDRESS -> "請輸入網域名稱，不要輸入 IP 位址。"
        ManualRuleDraft.Rejection.URL_OR_PATH ->
            "請只輸入網域，不要包含 https://、路徑、通訊埠或查詢字串。"
        ManualRuleDraft.Rejection.DUPLICATE -> "這個網域已經在清單中。"
    }
}
