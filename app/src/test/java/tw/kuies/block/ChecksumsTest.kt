package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.packet.Checksums
import tw.kuies.block.dns.packet.IP_PROTOCOL_UDP
import tw.kuies.block.dns.packet.Ipv4Parser
import tw.kuies.block.dns.packet.UdpParser

class ChecksumsTest {

    private fun header(): ByteArray = Ipv4Parser.buildHeader(
        sourceAddress = ipAddress(TestNet.CLIENT),
        destinationAddress = ipAddress(TestNet.DNS_SERVER),
        protocol = IP_PROTOCOL_UDP,
        payloadLength = 40,
        identification = 7
    )

    @Test
    fun builtIpv4HeaderValidates() {
        assertTrue(Checksums.verifyIpv4Header(header()))
    }

    @Test
    fun aSingleFlippedBitBreaksTheIpv4Checksum() {
        val packet = header()
        packet[12] = (packet[12].toInt() xor 0x01).toByte()
        assertFalse(Checksums.verifyIpv4Header(packet))
    }

    @Test
    fun builtUdpSegmentValidatesAgainstItsPseudoHeader() {
        val payload = byteArrayOf(1, 2, 3)
        val source = ipAddress(TestNet.CLIENT)
        val destination = ipAddress(TestNet.DNS_SERVER)
        val segment = UdpParser.buildSegment(4444, 53, payload, source, destination)
        assertTrue(Checksums.verifyUdpChecksum(source, destination, segment, 0, segment.size))
    }

    @Test
    fun udpChecksumDependsOnThePseudoHeader() {
        val payload = byteArrayOf(9, 9)
        val source = ipAddress(TestNet.CLIENT)
        val destination = ipAddress(TestNet.DNS_SERVER)
        val segment = UdpParser.buildSegment(4444, 53, payload, source, destination)
        // Same bytes, different destination: the pseudo-header changes, so it must no longer validate.
        assertFalse(
            Checksums.verifyUdpChecksum(source, ipAddress(TestNet.OTHER), segment, 0, segment.size)
        )
    }

    @Test
    fun oddLengthPadsTheFinalByte() {
        val sum = Checksums.onesComplementSum(byteArrayOf(0xAB.toByte()), 0, 1)
        assertEquals(0xAB00, Checksums.fold(sum))
        assertEquals(0x54FF, Checksums.finish(sum))
    }
}
