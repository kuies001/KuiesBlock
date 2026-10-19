package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.dns.DnsFallbackStatus
import tw.kuies.block.ui.DnsFallbackPresentation
import tw.kuies.block.ui.DnsFallbackTone

/** The card must never claim a working DNS path before the service reports one. */
class DnsFallbackPresentationTest {

    private fun text(
        state: ProtectionState,
        running: Boolean = true,
        enabled: Boolean = true,
        status: DnsFallbackStatus
    ) = DnsFallbackPresentation.statusText(state, running, enabled, status)

    private fun tone(
        state: ProtectionState,
        running: Boolean = true,
        enabled: Boolean = true,
        status: DnsFallbackStatus
    ) = DnsFallbackPresentation.tone(state, running, enabled, status)

    @Test
    fun theFiveServiceStatesGetTheRequiredWording() {
        assertEquals(
            "主要 DNS 正常",
            text(ProtectionState.ACTIVE, status = DnsFallbackStatus.PRIMARY)
        )
        assertEquals(
            "目前使用備援 DNS",
            text(ProtectionState.ACTIVE, status = DnsFallbackStatus.BACKUP)
        )
        assertEquals(
            "正在切換 DNS",
            text(ProtectionState.ACTIVE, status = DnsFallbackStatus.SWITCHING)
        )
        assertEquals(
            "DNS 連線失敗",
            text(ProtectionState.ACTIVE, status = DnsFallbackStatus.UNAVAILABLE)
        )
        assertEquals(
            "自動備援已關閉",
            text(ProtectionState.ACTIVE, enabled = false, status = DnsFallbackStatus.DISABLED)
        )
    }

    @Test
    fun aStoppedOrStartingTunnelNeverClaimsThePrimaryIsHealthy() {
        val states = listOf(
            ProtectionState.NOT_AUTHORIZED,
            ProtectionState.AUTHORIZED_IDLE,
            ProtectionState.STARTING
        )
        states.forEach { state ->
            assertEquals(
                "防護未啟動",
                text(state, status = DnsFallbackStatus.PRIMARY)
            )
        }
        assertEquals(
            "防護未啟動",
            text(ProtectionState.ACTIVE, running = false, status = DnsFallbackStatus.PRIMARY)
        )
    }

    @Test
    fun theFailureStateOnlySaysDnsFailedWhenDnsReallyFailed() {
        assertEquals(
            "DNS 連線失敗",
            text(ProtectionState.ERROR, status = DnsFallbackStatus.UNAVAILABLE)
        )
        assertEquals(
            "防護未啟動",
            text(ProtectionState.ERROR, status = DnsFallbackStatus.PRIMARY)
        )
    }

    @Test
    fun coloursFollowTheExistingSuccessNoticeNeutralErrorAndMutedRoles() {
        assertEquals(
            DnsFallbackTone.SUCCESS,
            tone(ProtectionState.ACTIVE, status = DnsFallbackStatus.PRIMARY)
        )
        assertEquals(
            DnsFallbackTone.WARNING,
            tone(ProtectionState.ACTIVE, status = DnsFallbackStatus.BACKUP)
        )
        assertEquals(
            DnsFallbackTone.NEUTRAL,
            tone(ProtectionState.ACTIVE, status = DnsFallbackStatus.SWITCHING)
        )
        assertEquals(
            DnsFallbackTone.ERROR,
            tone(ProtectionState.ACTIVE, status = DnsFallbackStatus.UNAVAILABLE)
        )
        assertEquals(
            DnsFallbackTone.MUTED,
            tone(ProtectionState.ACTIVE, enabled = false, status = DnsFallbackStatus.DISABLED)
        )
        assertEquals(
            DnsFallbackTone.MUTED,
            tone(ProtectionState.AUTHORIZED_IDLE, status = DnsFallbackStatus.PRIMARY)
        )
    }
}
