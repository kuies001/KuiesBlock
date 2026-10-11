package tw.kuies.block.dns.packet

/** A UDP datagram parsed out of an [Ipv4Packet] payload. */
class UdpDatagram(
    val sourcePort: Int,
    val destinationPort: Int,
    val udpLength: Int,
    private val parentBytes: ByteArray,
    private val segmentOffset: Int
) {
    val payloadOffset: Int get() = segmentOffset + UDP_HEADER_LENGTH

    val payloadLength: Int get() = udpLength - UDP_HEADER_LENGTH

    fun copyPayload(): ByteArray = parentBytes.copyOfRange(payloadOffset, payloadOffset + payloadLength)

    companion object {
        const val UDP_HEADER_LENGTH = 8
    }
}

sealed interface UdpParseOutcome {
    data class Ok(val datagram: UdpDatagram) : UdpParseOutcome
    data class Drop(val reason: PacketDropReason) : UdpParseOutcome
}

object UdpParser {

    fun parse(ip: Ipv4Packet): UdpParseOutcome {
        val offset = ip.payloadOffset
        val available = ip.payloadLength
        if (available < UdpDatagram.UDP_HEADER_LENGTH) {
            return UdpParseOutcome.Drop(PacketDropReason.TOO_SHORT)
        }
        val udpLength = Bytes.readUInt16(ip.bytes, offset + 4)
        if (udpLength < UdpDatagram.UDP_HEADER_LENGTH || udpLength > available) {
            return UdpParseOutcome.Drop(PacketDropReason.BAD_UDP_LENGTH)
        }
        val checksum = Bytes.readUInt16(ip.bytes, offset + 6)
        // A zero checksum means "not computed" for IPv4/UDP, which is legal.
        if (checksum != 0) {
            val valid = Checksums.verifyUdpChecksum(
                sourceAddress = ip.sourceAddress,
                destinationAddress = ip.destinationAddress,
                segment = ip.bytes,
                offset = offset,
                length = udpLength
            )
            if (!valid) {
                return UdpParseOutcome.Drop(PacketDropReason.BAD_UDP_CHECKSUM)
            }
        }
        return UdpParseOutcome.Ok(
            UdpDatagram(
                sourcePort = Bytes.readUInt16(ip.bytes, offset),
                destinationPort = Bytes.readUInt16(ip.bytes, offset + 2),
                udpLength = udpLength,
                parentBytes = ip.bytes,
                segmentOffset = offset
            )
        )
    }

    /** Builds a UDP segment (header + payload) with the checksum filled in. */
    fun buildSegment(
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        sourceAddress: Int,
        destinationAddress: Int
    ): ByteArray {
        val segment = ByteArray(UdpDatagram.UDP_HEADER_LENGTH + payload.size)
        Bytes.writeUInt16(segment, 0, sourcePort)
        Bytes.writeUInt16(segment, 2, destinationPort)
        Bytes.writeUInt16(segment, 4, segment.size)
        Bytes.writeUInt16(segment, 6, 0)
        System.arraycopy(payload, 0, segment, UdpDatagram.UDP_HEADER_LENGTH, payload.size)
        val checksum = Checksums.computeUdpChecksum(
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            segment = segment,
            offset = 0,
            length = segment.size
        )
        // A computed zero is transmitted as 0xFFFF so it is not confused with "no checksum".
        Bytes.writeUInt16(segment, 6, if (checksum == 0) 0xFFFF else checksum)
        return segment
    }
}
