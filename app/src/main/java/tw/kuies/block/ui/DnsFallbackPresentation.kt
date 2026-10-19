package tw.kuies.block.ui

import tw.kuies.block.core.ProtectionState
import tw.kuies.block.dns.DnsFallbackStatus

/** The colour intent of the DNS backup status line. */
enum class DnsFallbackTone { SUCCESS, WARNING, NEUTRAL, ERROR, MUTED }

/**
 * Pure wording for the DNS backup card.
 *
 * The service publishes what it actually verified; this object only decides how to say it. It
 * refuses to describe a stopped or still-starting tunnel as 「主要 DNS 正常」, because that claim is
 * only true after the service reached [ProtectionState.ACTIVE].
 */
object DnsFallbackPresentation {

    fun statusText(
        protectionState: ProtectionState,
        isServiceRunning: Boolean,
        enabled: Boolean,
        status: DnsFallbackStatus
    ): String {
        if (protectionState == ProtectionState.ERROR) {
            return if (status == DnsFallbackStatus.UNAVAILABLE) {
                "DNS 連線失敗"
            } else {
                "防護未啟動"
            }
        }
        if (!isServiceRunning || protectionState != ProtectionState.ACTIVE) {
            return "防護未啟動"
        }
        if (!enabled || status == DnsFallbackStatus.DISABLED) {
            return "自動備援已關閉"
        }
        return when (status) {
            DnsFallbackStatus.PRIMARY -> "主要 DNS 正常"
            DnsFallbackStatus.BACKUP -> "目前使用備援 DNS"
            DnsFallbackStatus.SWITCHING -> "正在切換 DNS"
            DnsFallbackStatus.UNAVAILABLE -> "DNS 連線失敗"
            DnsFallbackStatus.DISABLED -> "自動備援已關閉"
        }
    }

    fun tone(
        protectionState: ProtectionState,
        isServiceRunning: Boolean,
        enabled: Boolean,
        status: DnsFallbackStatus
    ): DnsFallbackTone {
        if (protectionState == ProtectionState.ERROR) {
            return if (status == DnsFallbackStatus.UNAVAILABLE) {
                DnsFallbackTone.ERROR
            } else {
                DnsFallbackTone.MUTED
            }
        }
        if (!isServiceRunning || protectionState != ProtectionState.ACTIVE) {
            return DnsFallbackTone.MUTED
        }
        if (!enabled || status == DnsFallbackStatus.DISABLED) {
            return DnsFallbackTone.MUTED
        }
        return when (status) {
            DnsFallbackStatus.PRIMARY -> DnsFallbackTone.SUCCESS
            DnsFallbackStatus.BACKUP -> DnsFallbackTone.WARNING
            DnsFallbackStatus.SWITCHING -> DnsFallbackTone.NEUTRAL
            DnsFallbackStatus.UNAVAILABLE -> DnsFallbackTone.ERROR
            DnsFallbackStatus.DISABLED -> DnsFallbackTone.MUTED
        }
    }
}
