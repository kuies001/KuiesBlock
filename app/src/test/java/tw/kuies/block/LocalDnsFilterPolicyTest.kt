package tw.kuies.block

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsMessageWriter
import tw.kuies.block.dns.message.DnsResponseOutcome
import tw.kuies.block.dns.message.DnsRcode
import tw.kuies.block.dns.packet.DnsReplyPolicy
import tw.kuies.block.dns.packet.DnsTunnelOutcome
import tw.kuies.block.dns.packet.DnsTunnelPacketParser
import tw.kuies.block.dns.packet.TunnelDnsQuery
import tw.kuies.block.filter.DomainSet
import tw.kuies.block.filter.FilterSnapshot
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.MatchScope
import tw.kuies.block.vpn.LocalDnsFilterPolicy

/**
 * The local rules are applied to a query taken from the tunnel, exactly as the service does it.
 *
 * Queries are built as real packets and pushed through the real parser, so the test also covers the
 * question name travelling from the wire into the filter.
 */
class LocalDnsFilterPolicyTest {

    private val budget = DnsReplyPolicy.ipv4UdpBudget(1500)

    private fun queryFor(
        name: String,
        type: Int = DnsMessageReader.TYPE_A,
        ednsSize: Int? = null
    ): TunnelDnsQuery {
        val payload = DnsMessageWriter.buildQuery(
            transactionId = 0x1234,
            name = name,
            type = type,
            ednsUdpPayloadSize = ednsSize
        )
        val packet = ipv4UdpPacket(
            sourceAddress = TestNet.CLIENT,
            destinationAddress = TestNet.DNS_SERVER,
            sourcePort = 34567,
            destinationPort = TestNet.DNS_PORT,
            payload = payload
        )
        val outcome = DnsTunnelPacketParser.parse(packet, packet.size, TestNet.dnsTarget)
        return (outcome as DnsTunnelOutcome.Query).query
    }

    private fun snapshotBlocking(vararg domains: String) =
        FilterSnapshot.build(subscriptionBlock = DomainSet.of(domains.toList()))

    // --- blocking ---------------------------------------------------------------------------

    @Test
    fun aBlockedNameGetsAnNxdomainReplyThatEchoesTheQuery() {
        val query = queryFor("ads.example.com")
        val snapshot = snapshotBlocking("example.com")

        val reply = LocalDnsFilterPolicy.blockReply(query, snapshot, budget)
        assertNotNull("a blocked name must be answered locally", reply)

        val parsed = DnsMessageReader.readResponse(reply!!, reply.size)
        val response = (parsed as DnsResponseOutcome.Ok).response
        assertEquals(DnsRcode.NXDOMAIN, response.rcode)
        assertEquals("the transaction id must be the client's", 0x1234, response.header.id)
        assertEquals("a block reply carries no answers", 0, response.answerCount)

        // The question section must survive byte for byte, or a stub resolver will ignore the reply.
        val questionEnd = (DnsMessageReader.readQuery(query.queryBytes, query.queryLength)
            as tw.kuies.block.dns.message.DnsQueryOutcome.Ok).query.questionEndOffset
        assertArrayEquals(
            query.queryBytes.copyOfRange(12, questionEnd),
            reply.copyOfRange(12, reply.size)
        )
    }

    @Test
    fun aSubdomainOfABlockedRuleIsBlocked() {
        val snapshot = snapshotBlocking("example.com")
        assertNotNull(LocalDnsFilterPolicy.blockReply(queryFor("a.b.example.com"), snapshot, budget))
        assertNotNull(LocalDnsFilterPolicy.blockReply(queryFor("EXAMPLE.COM."), snapshot, budget))
    }

    @Test
    fun everyQueryTypeIsBlockedTheSameWay() {
        val snapshot = snapshotBlocking("example.com")
        for (type in listOf(1, 28, 15, 16, 99, 255)) {
            assertNotNull(
                "QTYPE $type must be blocked",
                LocalDnsFilterPolicy.blockReply(queryFor("example.com", type = type), snapshot, budget)
            )
        }
    }

    /** A block reply must never be manufactured when it would not fit the link's UDP budget. */
    @Test
    fun aReplyThatWouldNotFitIsLeftToTheUpstreamPath() {
        val snapshot = snapshotBlocking("example.com")
        val query = queryFor("example.com")
        assertNull(LocalDnsFilterPolicy.blockReply(query, snapshot, udpBudget = 1))
    }

    // --- letting queries through ------------------------------------------------------------

    @Test
    fun anUnmatchedNameStillGoesUpstream() {
        val snapshot = snapshotBlocking("example.com")
        assertNull(LocalDnsFilterPolicy.blockReply(queryFor("unrelated.test"), snapshot, budget))
    }

    @Test
    fun aLookalikeNameIsNotBlocked() {
        val snapshot = snapshotBlocking("example.com")
        assertNull(LocalDnsFilterPolicy.blockReply(queryFor("notexample.com"), snapshot, budget))
        assertNull(LocalDnsFilterPolicy.blockReply(queryFor("example.com.evil.test"), snapshot, budget))
    }

    @Test
    fun anAllowedNameIsNotBlocked() {
        val snapshot = FilterSnapshot.build(
            subscriptionBlock = DomainSet.of(listOf("example.com")),
            subscriptionAllow = DomainSet.of(listOf("shop.example.com"))
        )
        assertNull(LocalDnsFilterPolicy.blockReply(queryFor("shop.example.com"), snapshot, budget))
        assertNotNull(LocalDnsFilterPolicy.blockReply(queryFor("other.example.com"), snapshot, budget))
    }

    @Test
    fun aManualAllowBeatsASubscriptionBlock() {
        val snapshot = FilterSnapshot.build(
            manualAllow = listOf(ManualRule("example.com", MatchScope.SUBDOMAIN)),
            subscriptionBlock = DomainSet.of(listOf("example.com"))
        )
        assertNull(LocalDnsFilterPolicy.blockReply(queryFor("www.example.com"), snapshot, budget))
    }

    @Test
    fun anEmptyRuleSetNeverBlocksAnything() {
        assertNull(
            LocalDnsFilterPolicy.blockReply(
                queryFor("example.com"),
                FilterSnapshot.EMPTY,
                budget
            )
        )
    }

    @Test
    fun theRootNameIsNeverBlocked() {
        // The root question has no domain to canonicalise, so it must not become a block.
        val snapshot = snapshotBlocking("example.com")
        assertNull(LocalDnsFilterPolicy.blockReply(queryFor("."), snapshot, budget))
    }
}
