package tw.kuies.block.ui

import tw.kuies.block.core.DnsPath
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.dns.PrivateDnsState

/**
 * How urgent the primary protection button should look.
 *
 * A pure function of the protection state (plus whether the service is still running), so every
 * screen derives the same colour and label from one place. It deliberately depends on nothing else
 * — no timers, no animation state — so the colour can never lag behind the real VPN state.
 */
enum class ProtectionActionTone {
    /** Turning protection on, or retrying after it stopped: harmless and expected. */
    ENABLE,

    /** Cancelling a start that is still in progress: reversible, worth a softer notice colour. */
    CAUTION,

    /** Stopping a tunnel that is intercepting (or failed while still running): the strongest cue. */
    DANGER
}

/** The Material 3 colour roles the protection button is allowed to use. */
enum class ProtectionActionColorRole { PRIMARY, SECONDARY_CONTAINER, ERROR_CONTAINER }

/**
 * Which private DNS service the system is actually pinned to, as far as it can be read.
 *
 * Only [OUR_HOSTNAME] may be described as filtered by AdGuard Home; [OTHER_HOSTNAME] and
 * [UNKNOWN_HOSTNAME] are services KuiesBlock knows nothing about and must never vouch for. The
 * device's own validation state is intentionally not represented: the app has no reliable way to
 * read it, so it is never claimed.
 */
enum class PrivateDnsTarget { NOT_STRICT, OUR_HOSTNAME, OTHER_HOSTNAME, UNKNOWN_HOSTNAME }

/**
 * Pure mapping from [ProtectionState] to the wording shown on the home screen.
 *
 * 「防護已啟用」 is only ever produced for [ProtectionState.ACTIVE], which the service reaches
 * only after the TUN interface exists **and** a real DoH round trip has succeeded. A unit test
 * pins that down so no other state can claim protection is on.
 */
object ProtectionPresentation {

    fun title(state: ProtectionState): String = when (state) {
        ProtectionState.NOT_AUTHORIZED,
        ProtectionState.AUTHORIZED_IDLE -> "防護已停用"
        ProtectionState.STARTING -> "正在啟用防護"
        ProtectionState.ACTIVE -> "防護已啟用"
        ProtectionState.ERROR -> "防護異常"
    }

    fun description(
        state: ProtectionState,
        error: ProtectionError?,
        dnsPath: DnsPath = DnsPath.INTERCEPTED
    ): String = when (state) {
        ProtectionState.NOT_AUTHORIZED ->
            "需要授權本機 VPN，KuiesBlock 才能接管系統 DNS 查詢。"
        ProtectionState.AUTHORIZED_IDLE ->
            "已取得 VPN 授權。啟動防護後，系統 DNS 查詢會加密送往 dns.kuies.tw。"
        ProtectionState.STARTING ->
            "正在建立本機 VPN 並確認 DNS 往返正常，尚未開始攔截。"
        ProtectionState.ACTIVE -> if (dnsPath == DnsPath.SYSTEM_PRIVATE_DNS) {
            // Kept short on purpose; the notice card carries the detail.
            "DNS 交由系統私人 DNS 處理；KuiesBlock 未攔截查詢。"
        } else {
            "系統 DNS 查詢已透過加密的 DoH 送往 dns.kuies.tw，一般網路流量不受影響。"
        }
        ProtectionState.ERROR ->
            "DNS 無法正常運作，已停止防護並恢復正常網路。" +
                (error?.let { "（${errorLabel(it)}）" } ?: "")
    }

    fun primaryActionLabel(state: ProtectionState, isServiceRunning: Boolean = false): String = when (state) {
        ProtectionState.ACTIVE, ProtectionState.STARTING -> "停用防護"
        ProtectionState.ERROR -> if (isServiceRunning) "停用防護" else "重試啟用"
        ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE -> "啟用防護"
    }

    /**
     * The colour intent behind [primaryActionLabel]. Derived from the same inputs, so the label and
     * the colour can never disagree: an `ENABLE`/`重試啟用` button is always brand primary, a
     * cancel-during-start is always the soft notice colour, and a stop is always the soft warning
     * colour.
     */
    fun actionTone(state: ProtectionState, isServiceRunning: Boolean = false): ProtectionActionTone = when (state) {
        ProtectionState.STARTING -> ProtectionActionTone.CAUTION
        ProtectionState.ACTIVE -> ProtectionActionTone.DANGER
        ProtectionState.ERROR ->
            if (isServiceRunning) ProtectionActionTone.DANGER else ProtectionActionTone.ENABLE
        ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE -> ProtectionActionTone.ENABLE
    }

    /** The single Material 3 colour role for a [tone]; the Compose layer only resolves the tokens. */
    fun actionColorRole(tone: ProtectionActionTone): ProtectionActionColorRole = when (tone) {
        ProtectionActionTone.ENABLE -> ProtectionActionColorRole.PRIMARY
        ProtectionActionTone.CAUTION -> ProtectionActionColorRole.SECONDARY_CONTAINER
        ProtectionActionTone.DANGER -> ProtectionActionColorRole.ERROR_CONTAINER
    }

    fun interceptionLabel(
        state: ProtectionState,
        dnsPath: DnsPath = DnsPath.INTERCEPTED
    ): String = when {
        // The tunnel is up but the system Private DNS is the path that resolves names: never say
        // 「攔截中」 for that, or the pill would overclaim.
        state == ProtectionState.ACTIVE && dnsPath == DnsPath.SYSTEM_PRIVATE_DNS ->
            "未攔截（系統私人 DNS）"
        ProtectionStateRules.isInterceptionActive(state) -> "DNS 攔截中"
        state == ProtectionState.STARTING -> "準備中"
        state == ProtectionState.ERROR -> "防護異常"
        else -> "尚未攔截"
    }

    /**
     * Pure classification of what the system's Private DNS is pinned to. Only [PrivateDnsTarget.OUR_HOSTNAME]
     * lets the UI say AdGuard Home is still filtering.
     */
    fun privateDnsTarget(state: PrivateDnsState, ourHost: String): PrivateDnsTarget = when {
        !state.isStrictHostname -> PrivateDnsTarget.NOT_STRICT
        state.pinsHostname(ourHost) -> PrivateDnsTarget.OUR_HOSTNAME
        state.hostname.isNullOrBlank() -> PrivateDnsTarget.UNKNOWN_HOSTNAME
        else -> PrivateDnsTarget.OTHER_HOSTNAME
    }

    fun errorLabel(error: ProtectionError): String = when (error) {
        ProtectionError.TUN_ESTABLISH_FAILED -> "無法建立本機 VPN 介面"
        ProtectionError.NO_UNDERLYING_NETWORK -> "找不到可用的網路"
        ProtectionError.ENDPOINT_NOT_DOH -> "DNS 傳輸設定無效"
        ProtectionError.DNS_UNREACHABLE -> "無法連線到 dns.kuies.tw"
        ProtectionError.DNS_TIMEOUT -> "DNS 查詢逾時"
        ProtectionError.TLS_FAILURE -> "TLS 憑證驗證失敗"
        ProtectionError.DNS_INVALID_RESPONSE -> "DNS 回應格式錯誤"
    }
}
