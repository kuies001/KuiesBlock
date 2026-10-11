package tw.kuies.block.ui

import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsEndpointProbe
import tw.kuies.block.dns.PrivateDnsMode

/** Pure mapping from DNS diagnostic values to display strings. */
object DnsPresentation {

    fun statusLabel(status: DnsCheckStatus): String = when (status) {
        DnsCheckStatus.NOT_CHECKED -> "尚未檢查"
        DnsCheckStatus.CHECKING -> "檢查中…"
        DnsCheckStatus.OK -> "連線正常"
        DnsCheckStatus.WARNING -> "部分異常"
        DnsCheckStatus.ERROR -> "連線異常"
    }

    fun probeSummary(probe: DnsEndpointProbe): String = when {
        probe.usable && probe.tlsHandshakeOk -> "可用（TLS 通過）"
        probe.usable -> "可用"
        !probe.tcpConnected -> "無法連線" + suffix(probe.errorMessage)
        else -> "TCP 已連線但 TLS 失敗" + suffix(probe.errorMessage)
    }

    fun privateDnsLabel(mode: PrivateDnsMode): String = when (mode) {
        PrivateDnsMode.OFF -> "關閉"
        PrivateDnsMode.OPPORTUNISTIC -> "自動"
        PrivateDnsMode.HOSTNAME -> "指定主機名稱"
        PrivateDnsMode.UNKNOWN -> "未知"
    }

    private fun suffix(error: String?): String =
        if (error.isNullOrBlank()) "" else "（$error）"
}
