package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.core.ProtectionStatusHolder
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.dns.PrivateDnsState
import tw.kuies.block.vpn.KuiesVpnService

class VpnStopLifecycleTest {

    @Test
    fun vpnServiceDeclaresExplicitStopAction() {
        assertEquals("tw.kuies.block.action.VPN_STOP", KuiesVpnService.ACTION_STOP)
        assertEquals("tw.kuies.block.action.VPN_START", KuiesVpnService.ACTION_START)
    }

    @Test
    fun stopActionPreemptsStartingState() {
        // When user toggles on, state moves to STARTING
        ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStarting(), null)
        assertEquals(ProtectionState.STARTING, ProtectionStatusHolder.state)
        assertFalse(ProtectionStateRules.isInterceptionActive(ProtectionStatusHolder.state))

        // When user cancels during STARTING, state reliably returns to AUTHORIZED_IDLE
        val stoppedState = ProtectionStateRules.afterServiceStopped()
        ProtectionStatusHolder.update(stoppedState, null)
        assertEquals(ProtectionState.AUTHORIZED_IDLE, ProtectionStatusHolder.state)
        assertNull(ProtectionStatusHolder.error)
        assertFalse(ProtectionStateRules.isInterceptionActive(ProtectionStatusHolder.state))
    }

    @Test
    fun stopActionPreemptsActiveState() {
        // Once verified, state is ACTIVE
        ProtectionStatusHolder.update(ProtectionStateRules.afterDnsVerified(), null)
        assertEquals(ProtectionState.ACTIVE, ProtectionStatusHolder.state)
        assertTrue(ProtectionStateRules.isInterceptionActive(ProtectionStatusHolder.state))

        // Tapping stop returns to AUTHORIZED_IDLE
        val stoppedState = ProtectionStateRules.afterServiceStopped()
        ProtectionStatusHolder.update(stoppedState, null)
        assertEquals(ProtectionState.AUTHORIZED_IDLE, ProtectionStatusHolder.state)
        assertNull(ProtectionStatusHolder.error)
        assertFalse(ProtectionStateRules.isInterceptionActive(ProtectionStatusHolder.state))
    }

    @Test
    fun stopActionCleansUpFromErrorState() {
        // Service encountered an error (e.g. fail-open)
        ProtectionStatusHolder.update(ProtectionStateRules.afterFailure(), ProtectionError.DNS_UNREACHABLE)
        assertEquals(ProtectionState.ERROR, ProtectionStatusHolder.state)
        assertEquals(ProtectionError.DNS_UNREACHABLE, ProtectionStatusHolder.error)

        // Stopping from ERROR clears error and returns to AUTHORIZED_IDLE
        ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStopped(), null)
        assertEquals(ProtectionState.AUTHORIZED_IDLE, ProtectionStatusHolder.state)
        assertNull(ProtectionStatusHolder.error)
    }

    @Test
    fun repeatedStopCallsAreIdempotent() {
        ProtectionStatusHolder.update(ProtectionStateRules.afterDnsVerified(), null)
        assertEquals(ProtectionState.ACTIVE, ProtectionStatusHolder.state)

        repeat(3) {
            ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStopped(), null)
            assertEquals(ProtectionState.AUTHORIZED_IDLE, ProtectionStatusHolder.state)
            assertNull(ProtectionStatusHolder.error)
        }
    }

    @Test
    fun repeatedStartCallsDoNotCorruptState() {
        ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRequest(true))
        assertEquals(ProtectionState.AUTHORIZED_IDLE, ProtectionStatusHolder.state)

        repeat(3) {
            ProtectionStatusHolder.update(ProtectionStateRules.afterServiceStarting(), null)
            assertEquals(ProtectionState.STARTING, ProtectionStatusHolder.state)
        }

        ProtectionStatusHolder.update(ProtectionStateRules.afterDnsVerified(), null)
        assertEquals(ProtectionState.ACTIVE, ProtectionStatusHolder.state)
    }

    @Test
    fun privateDnsStrictHostnameDetection() {
        val hostnameState = PrivateDnsState(PrivateDnsMode.HOSTNAME, "dns.kuies.tw")
        assertTrue(hostnameState.isStrictHostname)
        assertTrue(hostnameState.mayBypassInterception)
        assertTrue(hostnameState.pinsHostname("dns.kuies.tw"))

        val opportunisticState = PrivateDnsState(PrivateDnsMode.OPPORTUNISTIC, null)
        assertFalse(opportunisticState.isStrictHostname)
        assertTrue(opportunisticState.mayBypassInterception)

        val offState = PrivateDnsState(PrivateDnsMode.OFF, null)
        assertFalse(offState.isStrictHostname)
        assertFalse(offState.mayBypassInterception)
    }
}
