package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.packet.Bytes
import tw.kuies.block.dns.packet.Checksums
import tw.kuies.block.dns.packet.IP_PROTOCOL_UDP
import tw.kuies.block.dns.packet.Ipv4ParseOutcome
import tw.kuies.block.dns.packet.Ipv4Parser
import tw.kuies.block.dns.packet.PacketDropReason
import tw.kuies.block.dns.packet.UdpParseOutcome
import tw.kuies.block.dns.packet.UdpParser

class Ipv4ParserTest {

    private fun packet(): ByteArray =
        ipv4UdpPacket(TestNet.CLIENT, TestNet.DNS_SERVER, 5300, 53, "payload".toByteArray())

    private fun reason(outcome: Ipv4ParseOutcome): PacketDropReason =
        (outcome as Ipv4ParseOutcome.Drop).reason

    @Test
    fun parsesAWellFormedPacket() {
        val bytes = packet()
        val outcome = Ipv4Parser.parse(bytes, bytes.size)
        assertTrue(outcome is Ipv4ParseOutcome.Ok)
        val parsed = (outcome as Ipv4ParseOutcome.Ok).packet
        assertEquals(IP_PROTOCOL_UDP, parsed.protocol)
        assertEquals(TestNet.CLIENT, parsed.sourceAddressText)
        assertEquals(TestNet.DNS_SERVER, parsed.destinationAddressText)
        assertEquals(20, parsed.headerLength)
        assertEquals(bytes.size, parsed.totalLength)
    }

    @Test
    fun rejectsShortReads() {
        assertEquals(PacketDropReason.TOO_SHORT, reason(Ipv4Parser.parse(ByteArray(10), 10)))
    }

    @Test
    fun rejectsNonIpv4() {
        val bytes = packet()
        bytes[0] = 0x60
        assertEquals(PacketDropReason.NOT_IPV4, reason(Ipv4Parser.parse(bytes, bytes.size)))
    }

    @Test
    fun rejectsAnImpossibleHeaderLength() {
        val bytes = packet()
        bytes[0] = 0x40
        assertEquals(PacketDropReason.BAD_HEADER_LENGTH, reason(Ipv4Parser.parse(bytes, bytes.size)))
    }

    @Test
    fun rejectsATruncatedDatagram() {
        val bytes = packet()
        assertEquals(
            PacketDropReason.TRUNCATED,
            reason(Ipv4Parser.parse(bytes, bytes.size - 1))
        )
    }

    @Test
    fun rejectsACorruptHeader() {
        val bytes = packet()
        bytes[12] = (bytes[12].toInt() xor 0x0F).toByte()
        assertEquals(PacketDropReason.BAD_IP_CHECKSUM, reason(Ipv4Parser.parse(bytes, bytes.size)))
    }

    @Test
    fun rejectsFragmentsInsteadOfGuessing() {
        // More Fragments set.
        val moreFragments = withFragmentationField(packet(), 0x2000)
        assertEquals(
            PacketDropReason.FRAGMENTED,
            reason(Ipv4Parser.parse(moreFragments, moreFragments.size))
        )
        // Non-zero fragment offset.
        val offset = withFragmentationField(packet(), 0x0008)
        assertEquals(PacketDropReason.FRAGMENTED, reason(Ipv4Parser.parse(offset, offset.size)))
    }

    @Test
    fun udpParserRejectsABadChecksum() {
        val bytes = packet()
        Bytes.writeUInt16(bytes, 20 + 6, 0xBEEF)
        val ip = (Ipv4Parser.parse(bytes, bytes.size) as Ipv4ParseOutcome.Ok).packet
        val outcome = UdpParser.parse(ip)
        assertEquals(PacketDropReason.BAD_UDP_CHECKSUM, (outcome as UdpParseOutcome.Drop).reason)
    }

    @Test
    fun udpParserRejectsAnImpossibleLength() {
        val bytes = packet()
        Bytes.writeUInt16(bytes, 20 + 4, 0xFFFF)
        // Keep the IP header valid after patching the UDP length field.
        Bytes.writeUInt16(bytes, 10, 0)
        Bytes.writeUInt16(bytes, 10, Checksums.computeIpv4HeaderChecksum(bytes, 0, 20))
        val ip = (Ipv4Parser.parse(bytes, bytes.size) as Ipv4ParseOutcome.Ok).packet
        val outcome = UdpParser.parse(ip)
        assertEquals(PacketDropReason.BAD_UDP_LENGTH, (outcome as UdpParseOutcome.Drop).reason)
    }
}
