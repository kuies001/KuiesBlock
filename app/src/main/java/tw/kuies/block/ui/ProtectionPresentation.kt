package tw.kuies.block.ui

import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules

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

    fun description(state: ProtectionState, error: ProtectionError?): String = when (state) {
        ProtectionState.NOT_AUTHORIZED ->
            "需要授權本機 VPN，KuiesBlock 才能接管系統 DNS 查詢。"
        ProtectionState.AUTHORIZED_IDLE ->
            "已取得 VPN 授權。啟動防護後，系統 DNS 查詢會加密送往 dns.kuies.tw。"
        ProtectionState.STARTING ->
            "正在建立本機 VPN 並確認 DNS 往返正常，尚未開始攔截。"
        ProtectionState.ACTIVE ->
            "系統 DNS 查詢已透過加密的 DoH 送往 dns.kuies.tw，一般網路流量不受影響。"
        ProtectionState.ERROR ->
            "DNS 無法正常運作，已停止防護並恢復正常網路。" +
                (error?.let { "（${errorLabel(it)}）" } ?: "")
    }

    fun primaryActionLabel(state: ProtectionState, isServiceRunning: Boolean = false): String = when (state) {
        ProtectionState.ACTIVE, ProtectionState.STARTING -> "停用防護"
        ProtectionState.ERROR -> if (isServiceRunning) "停用防護" else "重試啟用"
        ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE -> "啟用防護"
    }

    fun interceptionLabel(state: ProtectionState): String = when {
        ProtectionStateRules.isInterceptionActive(state) -> "DNS 攔截中"
        state == ProtectionState.STARTING -> "準備中"
        state == ProtectionState.ERROR -> "防護異常"
        else -> "尚未攔截"
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
