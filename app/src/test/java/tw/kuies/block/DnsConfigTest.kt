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
    fun aTransportStoredByTheRemovedPickerCannotLeaveProtectionUnstartable() {
        // v0.2.x let the settings screen store DoT / plaintext / 尚未選擇, and the service refuses to
        // start on anything but DoH. The picker is gone, so the stored value must not decide the
        // transport any more — otherwise such an install could never start protection again.
        assertEquals(DnsProtocol.DOH, DnsProtocol.implementedOrDoh(DnsProtocol.DOT))
        assertEquals(DnsProtocol.DOH, DnsProtocol.implementedOrDoh(DnsProtocol.PLAINTEXT))
        assertEquals(DnsProtocol.DOH, DnsProtocol.implementedOrDoh(DnsProtocol.UNCONFIRMED))
        assertEquals(DnsProtocol.DOH, DnsProtocol.implementedOrDoh(null))
        assertEquals(DnsProtocol.DOH, DnsProtocol.implementedOrDoh(DnsProtocol.DOH))
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
