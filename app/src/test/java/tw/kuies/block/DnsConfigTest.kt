package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.DnsConfig
import tw.kuies.block.dns.DnsProtocol

class DnsConfigTest {

    @Test
    fun theDefaultEndpointIsKuiesDnsOverHttps() {
        val config = DnsConfig()
        assertEquals("dns.kuies.tw", config.host)
        assertEquals(DnsProtocol.DOH, config.protocol)
        assertTrue("the default transport must be encrypted", config.protocol.encrypted)
    }

    @Test
    fun unknownOrMissingStorageKeysFallBackToTheConfirmedDefault() {
        assertEquals(DnsProtocol.DOH, DnsProtocol.fromStorageKey(null))
        assertEquals(DnsProtocol.DOH, DnsProtocol.fromStorageKey("doh-v2-typo"))
    }

    @Test
    fun storageKeysRoundTrip() {
        DnsProtocol.entries.forEach { protocol ->
            assertEquals(protocol, DnsProtocol.fromStorageKey(protocol.storageKey))
        }
    }

    @Test
    fun encryptionFlagsAndPortsMatchTheProtocols() {
        assertTrue(DnsProtocol.DOH.encrypted)
        assertTrue(DnsProtocol.DOT.encrypted)
        assertEquals(443, DnsProtocol.DOH.defaultPort)
        assertEquals(853, DnsProtocol.DOT.defaultPort)
        assertFalse(DnsProtocol.PLAINTEXT.encrypted)
        assertEquals(53, DnsProtocol.PLAINTEXT.defaultPort)
        assertFalse(DnsProtocol.UNCONFIRMED.encrypted)
    }
}
