package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules

class ProtectionStateRulesTest {

    @Test
    fun grantedConsentMovesToAuthorizedIdle() {
        assertEquals(
            ProtectionState.AUTHORIZED_IDLE,
            ProtectionStateRules.afterAuthorizationRequest(granted = true)
        )
    }

    @Test
    fun deniedConsentStaysNotAuthorized() {
        assertEquals(
            ProtectionState.NOT_AUTHORIZED,
            ProtectionStateRules.afterAuthorizationRequest(granted = false)
        )
    }

    @Test
    fun startingThenVerifyingReachesActive() {
        assertEquals(ProtectionState.STARTING, ProtectionStateRules.afterServiceStarting())
        assertEquals(ProtectionState.ACTIVE, ProtectionStateRules.afterDnsVerified())
    }

    @Test
    fun stoppingFailureAndRevocationAreDistinct() {
        assertEquals(ProtectionState.AUTHORIZED_IDLE, ProtectionStateRules.afterServiceStopped())
        assertEquals(ProtectionState.ERROR, ProtectionStateRules.afterFailure())
        assertEquals(ProtectionState.NOT_AUTHORIZED, ProtectionStateRules.afterAuthorizationRevoked())
    }

    @Test
    fun onlyTheActiveStateCountsAsInterception() {
        ProtectionState.entries.forEach { state ->
            assertEquals(
                "isInterceptionActive($state)",
                state == ProtectionState.ACTIVE,
                ProtectionStateRules.isInterceptionActive(state)
            )
        }
    }

    @Test
    fun protectionCanRecoverAfterAFailure() {
        // Failing open drops to ERROR; re-enabling must walk back through STARTING to ACTIVE.
        val afterFailure = ProtectionStateRules.afterFailure()
        assertEquals(ProtectionState.ERROR, afterFailure)
        assertEquals(ProtectionState.STARTING, ProtectionStateRules.afterServiceStarting())
        assertEquals(ProtectionState.ACTIVE, ProtectionStateRules.afterDnsVerified())
    }
}
