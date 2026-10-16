package tw.kuies.block.ui

import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules

/** Which of the guide's two pages is on screen. */
enum class OnboardingPage {
    /** Page 1: explains what the VPN is for and asks for the permission. */
    VPN_PERMISSION,

    /** Page 2: turns protection on and reports what really happened. */
    START_PROTECTION
}

/**
 * The wording of the two-page first-run guide.
 *
 * Pure and free of Compose and of resources, exactly like [FilterPresentation] and
 * [ProtectionPresentation], so what the guide tells the user — and what it must never claim — can be
 * unit tested on the JVM. It owns no protection claim of its own: the second page's status line and
 * its failure copy are [ProtectionPresentation]'s, so a tunnel that merely started can no more read
 * as protection here than it can on the home screen.
 *
 * The guide is small on purpose. Page 1 says what the VPN is (an on-device DNS mechanism, not a
 * tunnel to a server) and asks for the permission; page 2 turns protection on and reports the real
 * state. Neither page mentions the DNS host, an endpoint, a transport or Private DNS.
 */
object OnboardingPresentation {

    // ---- page 1: the VPN permission ---------------------------------------------------------

    const val PERMISSION_TITLE = "讓 KuiesBlock 幫你擋廣告"

    const val PERMISSION_BODY =
        "KuiesBlock 使用 Android 的 VPN 功能，在手機上處理 DNS 查詢並攔截廣告網域。" +
            "這不代表將所有網路流量傳送到遠端 VPN 伺服器。"

    const val AUTHORIZE_ACTION = "授權 VPN"

    /** Shown instead of [AUTHORIZE_ACTION] once the authorization is already held. */
    const val CONTINUE_ACTION = "繼續"

    const val PERMISSION_GRANTED = "已完成 VPN 授權，不需要再授權一次。"

    const val PERMISSION_NEEDED = "需要這項授權才能開始防護。"

    /**
     * Shown while another app's VPN owns the system's default network.
     *
     * A plain notice and nothing more: the guide never stops, disables or otherwise touches that
     * VPN, it only tells the user why starting protection may not work until they close it.
     */
    const val OTHER_VPN_NOTICE =
        "偵測到其他 VPN 正在運作。KuiesBlock 不會關閉它，也不會停用它；" +
            "若授權後防護無法啟動，請先自行關閉該 VPN。"

    // ---- page 2: turning protection on ------------------------------------------------------

    const val PROTECTION_TITLE = "開啟防護，開始使用"

    const val PROTECTION_BODY =
        "在首頁開啟防護後，KuiesBlock 就會處理可攔截的 DNS 查詢。" +
            "預設使用標準模式，可以隨時在設定中調整強度。"

    const val START_ACTION = "開啟防護"

    /** Shown once protection really reached `ACTIVE`: it finishes the guide. */
    const val DONE_ACTION = "完成"

    const val STARTING_ACTION = "正在啟動防護…"

    const val SKIP_ACTION = "略過"

    // ---- wording ----------------------------------------------------------------------------

    fun pageTitle(page: OnboardingPage): String = when (page) {
        OnboardingPage.VPN_PERMISSION -> PERMISSION_TITLE
        OnboardingPage.START_PROTECTION -> PROTECTION_TITLE
    }

    fun pageBody(page: OnboardingPage): String = when (page) {
        OnboardingPage.VPN_PERMISSION -> PERMISSION_BODY
        OnboardingPage.START_PROTECTION -> PROTECTION_BODY
    }

    /** A plain "1／2" indicator, so the guide looks like the two short steps it is. */
    fun stepLabel(page: OnboardingPage): String = when (page) {
        OnboardingPage.VPN_PERMISSION -> "1／2"
        OnboardingPage.START_PROTECTION -> "2／2"
    }

    /**
     * Page 1's button.
     *
     * [vpnAuthorized] reflects the authorization the app really holds: when it is already granted the
     * button offers to continue instead of asking again, which is exactly what the brief forbids
     * re-asking about.
     */
    fun permissionActionLabel(vpnAuthorized: Boolean): String =
        if (vpnAuthorized) CONTINUE_ACTION else AUTHORIZE_ACTION

    /** Page 1's status line: never says anything is authorized unless it really is. */
    fun permissionStatus(vpnAuthorized: Boolean): String =
        if (vpnAuthorized) PERMISSION_GRANTED else PERMISSION_NEEDED

    /** The other-VPN notice, or null when no other VPN holds the slot. */
    fun otherVpnNotice(anotherVpnActive: Boolean): String? =
        if (anotherVpnActive) OTHER_VPN_NOTICE else null

    /**
     * Page 2's button.
     *
     * Only [ProtectionStateRules.isInterceptionActive] reaches [DONE_ACTION]: a tunnel that is still
     * starting, one that has not been started and one that failed all keep offering the start (or the
     * app's own retry/stop) instead, so the guide cannot present a started tunnel as working
     * filtering. `STARTING` is told apart from `INACTIVE` because it has its own, honest label.
     */
    fun protectionActionLabel(state: ProtectionState, isServiceRunning: Boolean = false): String =
        when {
            ProtectionStateRules.isInterceptionActive(state) -> DONE_ACTION
            state == ProtectionState.STARTING -> STARTING_ACTION
            state == ProtectionState.ERROR ->
                ProtectionPresentation.primaryActionLabel(state, isServiceRunning)
            else -> START_ACTION
        }

    /** False only while a start is in progress, where a second tap could not do anything useful. */
    fun protectionActionEnabled(state: ProtectionState): Boolean = state != ProtectionState.STARTING

    /**
     * Page 2's status line.
     *
     * The state headline comes from [ProtectionPresentation], so 「防護已啟用」 can only ever be
     * rendered for `ACTIVE` — the state the service reaches after the TUN interface exists *and* a
     * real DNS round trip succeeded.
     */
    fun protectionStatus(state: ProtectionState, isServiceRunning: Boolean = false): String =
        ProtectionPresentation.title(state, isServiceRunning)

    /** The app's existing failure reason, or null when protection did not fail. */
    fun protectionFailureReason(state: ProtectionState, error: ProtectionError?): String? =
        if (state == ProtectionState.ERROR) ProtectionPresentation.errorReason(error) else null

    /** The app's existing next step for a failure, or null when protection did not fail. */
    fun protectionFailureAdvice(state: ProtectionState, error: ProtectionError?): String? =
        if (state == ProtectionState.ERROR) ProtectionPresentation.errorAdvice(error) else null
}
