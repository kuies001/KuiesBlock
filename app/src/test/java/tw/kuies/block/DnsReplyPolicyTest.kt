package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.packet.DnsReplyPolicy

class DnsReplyPolicyTest {

    @Test
    fun udpBudgetForAStandardMtuLeavesRoomForHeaders() {
        assertEquals(1472, DnsReplyPolicy.ipv4UdpBudget(1500))
    }

    @Test
    fun aClientWithoutEdnsGetsTheFullLinkBudget() {
        // Falling back to 512 would force a truncated reply, and DNS-over-TCP is not proxied.
        assertEquals(1472, DnsReplyPolicy.udpPayloadBudget(null, DnsReplyPolicy.ipv4UdpBudget(1500)))
    }

    @Test
    fun aClientAdvertisedSizeIsClampedToTheLinkBudget() {
        val budget = DnsReplyPolicy.ipv4UdpBudget(1500)
        assertEquals(budget, DnsReplyPolicy.udpPayloadBudget(4096, budget))
        assertEquals(1232, DnsReplyPolicy.udpPayloadBudget(1232, budget))
        assertEquals(512, DnsReplyPolicy.udpPayloadBudget(512, budget))
    }

    @Test
    fun aTinyLinkBudgetIsNeverInflated() {
        assertEquals(200, DnsReplyPolicy.udpPayloadBudget(null, 200))
        assertEquals(200, DnsReplyPolicy.udpPayloadBudget(4096, 200))
    }

    @Test
    fun fitsInUdpComparesAgainstTheBudget() {
        assertTrue(DnsReplyPolicy.fitsInUdp(1472, 1472))
        assertFalse(DnsReplyPolicy.fitsInUdp(1473, 1472))
    }
}
