package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.ui.ProtectionPresentation

class ProtectionPresentationTest {

    @Test
    fun onlyTheActiveStateClaimsProtectionIsEnabled() {
        ProtectionState.entries.forEach { state ->
            val claimsEnabled = listOf(
                ProtectionPresentation.title(state),
                ProtectionPresentation.description(state, null)
            ).any { it.contains("防護已啟用") }
            assertEquals("state=$state", state == ProtectionState.ACTIVE, claimsEnabled)
        }
    }

    @Test
    fun interceptionLabelMatchesReality() {
        ProtectionState.entries.forEach { state ->
            val active = ProtectionStateRules.isInterceptionActive(state)
            val label = ProtectionPresentation.interceptionLabel(state)
            assertEquals("state=$state label=$label", active, label.contains("攔截中"))
        }
    }

    @Test
    fun theErrorStateExplainsWhyItStopped() {
        val label = ProtectionPresentation.errorLabel(ProtectionError.TLS_FAILURE)
        val description = ProtectionPresentation.description(ProtectionState.ERROR, ProtectionError.TLS_FAILURE)
        assertTrue(description.contains("停止"))
        assertTrue(description.contains(label))
    }

    @Test
    fun stateTitlesMatchDesignSpecification() {
        assertEquals("防護已停用", ProtectionPresentation.title(ProtectionState.NOT_AUTHORIZED))
        assertEquals("防護已停用", ProtectionPresentation.title(ProtectionState.AUTHORIZED_IDLE))
        assertEquals("正在啟用防護", ProtectionPresentation.title(ProtectionState.STARTING))
        assertEquals("防護已啟用", ProtectionPresentation.title(ProtectionState.ACTIVE))
        assertEquals("防護異常", ProtectionPresentation.title(ProtectionState.ERROR))
    }

    @Test
    fun primaryActionOffersStopWhileRunning() {
        assertEquals("停用防護", ProtectionPresentation.primaryActionLabel(ProtectionState.ACTIVE))
        assertEquals("停用防護", ProtectionPresentation.primaryActionLabel(ProtectionState.STARTING))
        assertEquals("啟用防護", ProtectionPresentation.primaryActionLabel(ProtectionState.AUTHORIZED_IDLE))
        assertEquals("重試啟用", ProtectionPresentation.primaryActionLabel(ProtectionState.ERROR, isServiceRunning = false))
        assertEquals("停用防護", ProtectionPresentation.primaryActionLabel(ProtectionState.ERROR, isServiceRunning = true))
        assertEquals("啟用防護", ProtectionPresentation.primaryActionLabel(ProtectionState.NOT_AUTHORIZED))
    }

    @Test
    fun everyProtectionErrorHasALabel() {
        ProtectionError.entries.forEach { error ->
            assertTrue("$error has no label", ProtectionPresentation.errorLabel(error).isNotBlank())
        }
    }
}
