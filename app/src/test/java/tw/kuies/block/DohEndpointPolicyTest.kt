package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.DnsConfig
import tw.kuies.block.dns.DnsProtocol
import tw.kuies.block.doh.DohEndpointOutcome
import tw.kuies.block.doh.DohEndpointPolicy
import tw.kuies.block.doh.DohFailure

class DohEndpointPolicyTest {

    @Test
    fun buildsAnHttpsUrlFromTheCentralConfig() {
        val outcome = DohEndpointPolicy.build(DnsConfig(protocol = DnsProtocol.DOH))
        assertTrue(outcome is DohEndpointOutcome.Ok)
        val endpoint = (outcome as DohEndpointOutcome.Ok).endpoint
        assertEquals("https://dns.kuies.tw/dns-query", endpoint.url)
        assertEquals("dns.kuies.tw", endpoint.host)
        assertEquals(443, endpoint.port)
    }

    @Test
    fun refusesAProtocolThatIsNotDoh() {
        listOf(DnsProtocol.UNCONFIRMED, DnsProtocol.DOT, DnsProtocol.PLAINTEXT).forEach { protocol ->
            val outcome = DohEndpointPolicy.build(DnsConfig(protocol = protocol))
            assertEquals(DohFailure.PROTOCOL_NOT_DOH, (outcome as DohEndpointOutcome.Invalid).failure)
        }
    }

    @Test
    fun normalisesAMissingLeadingSlash() {
        val config = DnsConfig(protocol = DnsProtocol.DOH, dohPath = "dns-query")
        val endpoint = (DohEndpointPolicy.build(config) as DohEndpointOutcome.Ok).endpoint
        assertEquals("https://dns.kuies.tw/dns-query", endpoint.url)
    }

    @Test
    fun refusesABlankOrStructuralHost() {
        listOf("", "   ", "evil.example/path").forEach { host ->
            val config = DnsConfig(protocol = DnsProtocol.DOH, host = host)
            assertEquals(
                DohFailure.INVALID_ENDPOINT,
                (DohEndpointPolicy.build(config) as DohEndpointOutcome.Invalid).failure
            )
        }
    }

    @Test
    fun httpsIsRequired() {
        assertTrue(DohEndpointPolicy.isHttps("https://dns.kuies.tw/dns-query"))
        assertFalse(DohEndpointPolicy.isHttps("http://dns.kuies.tw/dns-query"))
    }
}
