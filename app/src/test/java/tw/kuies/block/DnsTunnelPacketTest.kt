package tw.kuies.block

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsMessageWriter
import tw.kuies.block.dns.message.DnsRcode
import tw.kuies.block.dns.packet.DnsTunnelOutcome
import tw.kuies.block.dns.packet.DnsTunnelPacketParser
import tw.kuies.block.dns.packet.IP_PROTOCOL_UDP
import tw.kuies.block.dns.packet.Ipv4ParseOutcome
import tw.kuies.block.dns.packet.Ipv4Parser
import tw.kuies.block.dns.packet.PacketDropReason
import tw.kuies.block.dns.packet.UdpDnsReplyWriter
import tw.kuies.block.dns.packet.UdpParseOutcome
import tw.kuies.block.dns.packet.UdpParser

class DnsTunnelPacketTest {

    private fun queryPacket(
        destinationAddress: String = TestNet.DNS_SERVER,
        destinationPort: Int = TestNet.DNS_PORT,
        protocol: Int = IP_PROTOCOL_UDP,
        markAsResponse: Boolean = false
    ): ByteArray {
        val dns = DnsMessageWriter.buildQuery(0x2222, "ads.example.com", DnsMessageReader.TYPE_A, 1232)
        if (markAsResponse) dns[2] = (dns[2].toInt() or 0x80).toByte()
        return ipv4UdpPacket(
            sourceAddress = TestNet.CLIENT,
            destinationAddress = destinationAddress,
            sourcePort = 5300,
            destinationPort = destinationPort,
            payload = dns,
            protocol = protocol
        )
    }

    private fun dropReason(outcome: DnsTunnelOutcome): PacketDropReason =
        (outcome as DnsTunnelOutcome.Drop).reason

    @Test
    fun extractsADnsQueryFromTheTunnel() {
        val packet = queryPacket()
        val outcome = DnsTunnelPacketParser.parse(packet, packet.size, TestNet.dnsTarget)
        assertTrue(outcome is DnsTunnelOutcome.Query)
        val query = (outcome as DnsTunnelOutcome.Query).query
        assertEquals(0x2222, query.transactionId)
        assertEquals(ipAddress(TestNet.CLIENT), query.clientAddress)
        assertEquals(5300, query.clientPort)
        assertEquals(1232, query.ednsUdpPayloadSize)
    }

    @Test
    fun dropsTrafficAddressedElsewhere() {
        val packet = queryPacket(destinationAddress = TestNet.OTHER)
        assertEquals(
            PacketDropReason.NOT_TARGET_ADDRESS,
            dropReason(DnsTunnelPacketParser.parse(packet, packet.size, TestNet.dnsTarget))
        )
    }

    @Test
    fun dropsNonDnsPorts() {
        val packet = queryPacket(destinationPort = 443)
        assertEquals(
            PacketDropReason.NOT_DNS_PORT,
            dropReason(DnsTunnelPacketParser.parse(packet, packet.size, TestNet.dnsTarget))
        )
    }

    @Test
    fun dropsTcpDnsInsteadOfGuessing() {
        val packet = queryPacket(protocol = 6)
        assertEquals(
            PacketDropReason.NOT_UDP,
            dropReason(DnsTunnelPacketParser.parse(packet, packet.size, TestNet.dnsTarget))
        )
    }

    @Test
    fun dropsAResponseMasqueradingAsAQuery() {
        val packet = queryPacket(markAsResponse = true)
        assertEquals(
            PacketDropReason.NOT_A_QUERY,
            dropReason(DnsTunnelPacketParser.parse(packet, packet.size, TestNet.dnsTarget))
        )
    }

    @Test
    fun dropsMalformedDnsPayload() {
        val packet = ipv4UdpPacket(
            sourceAddress = TestNet.CLIENT,
            destinationAddress = TestNet.DNS_SERVER,
            sourcePort = 5300,
            destinationPort = TestNet.DNS_PORT,
            payload = byteArrayOf(1, 2, 3)
        )
        assertEquals(
            PacketDropReason.MALFORMED_DNS,
            dropReason(DnsTunnelPacketParser.parse(packet, packet.size, TestNet.dnsTarget))
        )
    }

    @Test
    fun replyRoundTripsThroughTheParserWithValidChecksums() {
        val packet = queryPacket()
        val query = (DnsTunnelPacketParser.parse(packet, packet.size, TestNet.dnsTarget)
            as DnsTunnelOutcome.Query).query
        val dnsResponse = DnsMessageWriter.responseHeaderOnly(
            query.queryBytes,
            query.queryLength,
            DnsRcode.NOERROR,
            truncated = false
        )

        val reply = UdpDnsReplyWriter.buildReply(
            query = query,
            sourceAddress = TestNet.dnsTarget.address,
            sourcePort = TestNet.dnsTarget.port,
            dnsPayload = dnsResponse,
            identification = 0x0101
        )

        val ip = (Ipv4Parser.parse(reply, reply.size) as Ipv4ParseOutcome.Ok).packet
        assertEquals(TestNet.DNS_SERVER, ip.sourceAddressText)
        assertEquals(TestNet.CLIENT, ip.destinationAddressText)

        // UdpParser verifies the checksum, so a valid parse also proves the checksum is correct.
        val udp = (UdpParser.parse(ip) as UdpParseOutcome.Ok).datagram
        assertEquals(TestNet.DNS_PORT, udp.sourcePort)
        assertEquals(5300, udp.destinationPort)
        assertArrayEquals(dnsResponse, udp.copyPayload())
    }
}
