package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.vpn.OtherVpnPolicy
import tw.kuies.block.vpn.OtherVpnState

/**
 * The three-state judgement behind the boot policy.
 *
 * The mapping is pure, so the cases the platform reader cannot produce on demand — an unnamed owner, an
 * owner that is us — can still be pinned. What matters for safety is the direction of the two defaults:
 * a VPN transport that is not provably ours counts as PRESENT, and the reader's "could not look" cases
 * are UNKNOWN rather than a quiet "no" (see `OtherVpnProbe`).
 */
class OtherVpnPolicyTest {

    private val ourUid = 10_670
    private val otherUid = 10_665

    @Test
    fun `no vpn transport is a positively established absent`() {
        assertEquals(
            OtherVpnState.ABSENT,
            OtherVpnPolicy.state(false, otherUid, ourUid, ourTunnelRunning = false)
        )
    }

    @Test
    fun `our own tunnel is never somebody else's vpn`() {
        assertEquals(
            OtherVpnState.ABSENT,
            OtherVpnPolicy.state(true, ourUid, ourUid, ourTunnelRunning = true)
        )
        // The moment just after our tunnel stops, when the platform can still report it for a frame.
        assertEquals(
            OtherVpnState.ABSENT,
            OtherVpnPolicy.state(true, ourUid, ourUid, ourTunnelRunning = false)
        )
    }

    @Test
    fun `a named foreign owner with our tunnel down is present`() {
        assertEquals(
            OtherVpnState.PRESENT,
            OtherVpnPolicy.state(true, otherUid, ourUid, ourTunnelRunning = false)
        )
    }

    @Test
    fun `an unnamed owner with our tunnel down is still present, not absent`() {
        // Below Android 10 the platform reports no owner uid; the VPN network is then not ours, because
        // our tunnel is not running, and guessing "probably ours" would take another app's slot.
        assertEquals(
            OtherVpnState.PRESENT,
            OtherVpnPolicy.state(true, null, ourUid, ourTunnelRunning = false)
        )
    }

    @Test
    fun `an unnamed owner with our tunnel up is ours`() {
        assertEquals(
            OtherVpnState.ABSENT,
            OtherVpnPolicy.state(true, null, ourUid, ourTunnelRunning = true)
        )
    }

    @Test
    fun `the guide's plain notice only fires on the positive case`() {
        assertTrue(OtherVpnPolicy.anotherVpnHoldsTheSlot(true, otherUid, ourUid, false))
        assertFalse(OtherVpnPolicy.anotherVpnHoldsTheSlot(false, otherUid, ourUid, false))
        assertFalse(OtherVpnPolicy.anotherVpnHoldsTheSlot(true, ourUid, ourUid, false))
        assertFalse(OtherVpnPolicy.anotherVpnHoldsTheSlot(true, null, ourUid, true))
    }
}
