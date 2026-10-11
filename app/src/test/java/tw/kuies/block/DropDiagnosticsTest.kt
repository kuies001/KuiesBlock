package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.packet.PacketDropReason
import tw.kuies.block.vpn.DropDiagnostics

/**
 * The debug-only drop description. Its whole job is to prove what a dropped tunnel packet was —
 * IPv4 vs IPv6 and which transport port — without ever touching an address or the payload.
 */
class DropDiagnosticsTest {

    /** A minimal IPv4 packet with a transport header carrying [destinationPort]. */
    private fun ipv4(protocol: Int, destinationPort: Int, totalLength: Int = 24): ByteArray {
        val packet = ByteArray(totalLength)
        packet[0] = 0x45.toByte() // version 4, IHL 5 (20 bytes)
        packet[2] = ((totalLength ushr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[9] = protocol.toByte()
        if (totalLength >= 24) {
            packet[20 + 2] = ((destinationPort ushr 8) and 0xFF).toByte()
            packet[20 + 3] = (destinationPort and 0xFF).toByte()
        }
        return packet
    }

    @Test
    fun reportsATcpDroppedPacketWithItsDestinationPort() {
        val detail = DropDiagnostics.describe(PacketDropReason.NOT_UDP, ipv4(protocol = 6, destinationPort = 853), 24)
        assertTrue("detail=$detail", detail!!.contains("ipv4 proto=6 dstPort=853"))
        assertTrue("detail=$detail", detail.contains("reason=NOT_UDP"))
    }

    @Test
    fun tellsAPlainDnsTcpRetryApartFromAnotherPort() {
        val toPort53 = DropDiagnostics.describe(PacketDropReason.NOT_UDP, ipv4(protocol = 6, destinationPort = 53), 24)
        val toPort853 = DropDiagnostics.describe(PacketDropReason.NOT_UDP, ipv4(protocol = 6, destinationPort = 853), 24)
        assertTrue(toPort53!!.contains("dstPort=53"))
        assertTrue(toPort853!!.contains("dstPort=853"))
    }

    @Test
    fun reportsAnIpv6PacketByItsNextHeader() {
        val packet = ByteArray(40)
        packet[0] = 0x60.toByte() // version 6
        packet[6] = 6 // next header: TCP
        val detail = DropDiagnostics.describe(PacketDropReason.NOT_IPV4, packet, 40)
        assertTrue("detail=$detail", detail!!.contains("ipv6 nextHeader=6"))
    }

    @Test
    fun reportsAnUnknownIpVersionPlainly() {
        val packet = ByteArray(20)
        packet[0] = 0x50.toByte() // version 5
        assertEquals("tunnelDrop ipv=5 reason=NOT_IPV4", DropDiagnostics.describe(PacketDropReason.NOT_IPV4, packet, 20))
    }

    @Test
    fun reportsTheFamilyEvenForATooShortBuffer() {
        // Version is readable from the first byte, so a short buffer still gets a plain family line.
        assertEquals(
            "tunnelDrop ipv=0 reason=TOO_SHORT",
            DropDiagnostics.describe(PacketDropReason.TOO_SHORT, ByteArray(10), 10)
        )
    }

    @Test
    fun returnsNullWhenTheDeclaredLengthExceedsTheBuffer() {
        // The declared length cannot exceed the buffer actually read.
        assertNull(DropDiagnostics.describe(PacketDropReason.TRUNCATED, ipv4(6, 53), 100))
        assertNull(DropDiagnostics.describe(PacketDropReason.TOO_SHORT, ByteArray(0), 0))
    }

    @Test
    fun aPortThatDoesNotFitReportsMinusOne() {
        val packet = ipv4(protocol = 6, destinationPort = 53)
        // The transport header would end past the captured length.
        assertEquals(-1, DropDiagnostics.destinationPort(packet, length = 22, transportOffset = 20))
        assertEquals(53, DropDiagnostics.destinationPort(packet, length = 24, transportOffset = 20))
    }

    @Test
    fun anIpv6PacketTooShortToHoldTheHeaderStillDescribesTheFamily() {
        val packet = ByteArray(20)
        packet[0] = 0x60.toByte() // version 6, but shorter than a real IPv6 header
        val detail = DropDiagnostics.describe(PacketDropReason.NOT_IPV4, packet, 20)
        assertEquals("tunnelDrop ipv6 reason=NOT_IPV4", detail)
    }
}
