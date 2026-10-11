package tw.kuies.block.ui

import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsConnectionScenario
import tw.kuies.block.dns.DnsEndpointProbe
import tw.kuies.block.dns.DnsProbeFailure
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.dns.PrivateDnsState

/**
 * Pure mapping from DNS diagnostic values to the wording shown to the user.
 *
 * Two hard rules, both pinned down by unit tests:
 *  * Nothing here may render a resolved address or a raw exception, so a screenshot or a support
 *    thread cannot leak the user's own DNS server details.
 *  * A Private DNS setting only ever adds the bypass wording. It never turns a failure into
 *    "connected", and a real failure is never softened into a benign warning.
 */
object DnsPresentation {

    fun statusLabel(status: DnsCheckStatus): String = when (status) {
        DnsCheckStatus.NOT_CHECKED -> "尚未檢查"
        DnsCheckStatus.CHECKING -> "檢查中…"
        DnsCheckStatus.OK -> "連線正常"
        DnsCheckStatus.WARNING -> "部分異常"
        DnsCheckStatus.ERROR -> "連線異常"
    }

    fun scenarioLabel(scenario: DnsConnectionScenario): String = when (scenario) {
        DnsConnectionScenario.CONNECTED -> "連線正常"
        DnsConnectionScenario.BYPASS_RISK -> "DNS 正常，可能繞過攔截"
        DnsConnectionScenario.FAILED -> "連線異常"
        DnsConnectionScenario.UNKNOWN -> "尚未檢查"
    }

    /** Short home-card line; null when the status label already says everything. */
    fun scenarioShortHint(scenario: DnsConnectionScenario): String? = when (scenario) {
        DnsConnectionScenario.BYPASS_RISK -> "部分查詢可能由系統私人 DNS 直接處理。"
        DnsConnectionScenario.FAILED -> "請至設定查看失敗原因。"
        else -> null
    }

    /** Fuller explanation, deliberately shown in one place only (the settings screen). */
    fun scenarioExplanation(
        scenario: DnsConnectionScenario,
        privateDns: PrivateDnsState,
        ourHost: String
    ): String? = when (scenario) {
        DnsConnectionScenario.BYPASS_RISK -> buildString {
            append("系統私人 DNS 可能直接處理部分查詢，KuiesBlock 的攔截統計可能不完整。")
            if (privateDns.pinsHostname(ourHost)) {
                append("系統私人 DNS 與 KuiesBlock 使用相同的 DNS 伺服器，過濾規則仍可能生效，")
                append("但部分查詢可能不經過 KuiesBlock。")
            }
        }
        DnsConnectionScenario.FAILED -> "DoH、TLS 或名稱解析失敗，已保留實際的失敗原因。"
        else -> null
    }

    fun probeSummary(probe: DnsEndpointProbe): String = when {
        probe.usable && probe.tlsHandshakeOk -> "可用（TLS 通過）"
        probe.usable -> "可用"
        !probe.tcpConnected -> probeFailureLabel(probe.failure)
        else -> "TCP 可連線，但 TLS 失敗（${probeFailureLabel(probe.failure)}）"
    }

    fun probeFailureLabel(failure: DnsProbeFailure?): String = when (failure) {
        DnsProbeFailure.CONNECTION_FAILED -> "無法連線"
        DnsProbeFailure.TIMEOUT -> "連線逾時"
        DnsProbeFailure.TLS_HANDSHAKE_FAILED -> "TLS 交握失敗"
        DnsProbeFailure.TLS_CERTIFICATE_REJECTED -> "TLS 憑證驗證失敗"
        null -> "無法使用"
    }

    fun resolutionFailureLabel(): String = "無法解析 DNS 主機名稱（底層網路 DNS 沒有回應）"

    fun privateDnsLabel(mode: PrivateDnsMode): String = when (mode) {
        PrivateDnsMode.OFF -> "關閉"
        PrivateDnsMode.OPPORTUNISTIC -> "自動"
        PrivateDnsMode.HOSTNAME -> "指定主機名稱"
        PrivateDnsMode.UNKNOWN -> "未知"
    }

    /**
     * Reports only *whether* the pinned private-DNS host matches ours. The hostname itself is never
     * shown, so an internal server name the user pinned cannot leak through the UI.
     */
    fun privateDnsMatchLabel(privateDns: PrivateDnsState, ourHost: String): String? =
        if (privateDns.isStrictHostname && privateDns.pinsHostname(ourHost)) "與 KuiesBlock 相同" else null
}
