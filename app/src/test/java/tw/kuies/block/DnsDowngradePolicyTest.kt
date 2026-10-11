package tw.kuies.block

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.DnsDowngradePolicy
import tw.kuies.block.dns.DnsProtocol

class DnsDowngradePolicyTest {

    @Test
    fun encryptedToPlainIsASilentDowngrade() {
        assertTrue(DnsDowngradePolicy.isSilentDowngrade(DnsProtocol.DOH, DnsProtocol.PLAINTEXT))
        assertTrue(DnsDowngradePolicy.isSilentDowngrade(DnsProtocol.DOT, DnsProtocol.PLAINTEXT))
    }

    @Test
    fun otherTransitionsAreNotFlaggedAsDowngrades() {
        assertFalse(DnsDowngradePolicy.isSilentDowngrade(DnsProtocol.UNCONFIRMED, DnsProtocol.PLAINTEXT))
        assertFalse(DnsDowngradePolicy.isSilentDowngrade(DnsProtocol.DOH, DnsProtocol.DOT))
        assertFalse(DnsDowngradePolicy.isSilentDowngrade(DnsProtocol.PLAINTEXT, DnsProtocol.DOH))
    }
}
