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

    // --- temporary endpoint form (Private DNS investigation) ------------------------------------

    private fun writeIpv4(bytes: ByteArray, offset: Int, a: Int, b: Int, c: Int, d: Int) {
        bytes[offset] = a.toByte()
        bytes[offset + 1] = b.toByte()
        bytes[offset + 2] = c.toByte()
        bytes[offset + 3] = d.toByte()
    }

    /** An IPv4 TCP SYN towards the virtual DNS on port 853, as the resolver's DoT would look. */
    private fun ipv4TcpSynToPort853(): ByteArray {
        val packet = ByteArray(40)
        packet[0] = 0x45.toByte() // version 4, IHL 5
        packet[3] = 40 // total length
        packet[9] = 6 // TCP
        writeIpv4(packet, 12, 10, 111, 222, 1) // source
        writeIpv4(packet, 16, 10, 111, 222, 2) // destination: the virtual DNS
        packet[20 + 2] = ((853 ushr 8) and 0xFF).toByte()
        packet[20 + 3] = (853 and 0xFF).toByte()
        packet[20 + 13] = 0x02 // SYN
        return packet
    }

    @Test
    fun theEndpointFormRevealsWhereTheTcpFlowWasGoing() {
        val detail = DropDiagnostics.describeWithEndpoints(PacketDropReason.NOT_UDP, ipv4TcpSynToPort853(), 40)
        assertTrue("detail=$detail", detail!!.contains("proto=6 dstPort=853"))
        assertTrue("detail=$detail", detail.contains("src=10.111.222.1"))
        assertTrue("detail=$detail", detail.contains("dst=10.111.222.2"))
        assertTrue("detail=$detail", detail.contains("flags=SYN"))
    }

    @Test
    fun theCoarseFormNeverPrintsAnAddress() {
        val detail = DropDiagnostics.describe(PacketDropReason.NOT_UDP, ipv4TcpSynToPort853(), 40)
        assertTrue("detail=$detail", !detail!!.contains("src="))
        assertTrue("detail=$detail", !detail.contains("dst="))
        assertTrue("detail=$detail", !detail.contains("10.111.222."))
    }

    @Test
    fun theEndpointFormPrintsBothIpv6Addresses() {
        val packet = ByteArray(40)
        packet[0] = 0x60.toByte() // version 6
        packet[6] = 58 // ICMPv6
        packet[8] = 0xFE.toByte()
        packet[9] = 0x80.toByte()
        packet[23] = 1 // fe80::1
        packet[39] = 0xFF.toByte() // ::ff
        val detail = DropDiagnostics.describeWithEndpoints(PacketDropReason.NOT_IPV4, packet, 40)
        assertTrue("detail=$detail", detail!!.contains("ipv6 nextHeader=58"))
        assertTrue("detail=$detail", detail.contains("src=fe80:0:0:0:0:0:0:1"))
        assertTrue("detail=$detail", detail.contains("dst=0:0:0:0:0:0:0:ff"))
    }

    @Test
    fun tcpFlagsAreUnknownWhenTheFlagsByteWasNotCaptured() {
        // Length stops before the TCP flags byte: the flags must read "-", never a guess.
        val packet = ipv4(protocol = 6, destinationPort = 853, totalLength = 30)
        assertEquals("-", DropDiagnostics.tcpFlags(packet, 30, transportOffset = 20))
        val detail = DropDiagnostics.describeWithEndpoints(PacketDropReason.NOT_UDP, packet, 30)
        assertTrue("detail=$detail", detail!!.contains("flags=-"))
    }
}
