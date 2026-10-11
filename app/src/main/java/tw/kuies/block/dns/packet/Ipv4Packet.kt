package tw.kuies.block.dns.packet

/**
 * Non-sensitive reason a packet read from the tunnel was discarded.
 *
 * Logging these (and nothing else) keeps diagnostics useful without ever writing DNS query
 * contents or addresses to Logcat.
 */
enum class PacketDropReason {
    TOO_SHORT,
    NOT_IPV4,
    BAD_HEADER_LENGTH,
    BAD_TOTAL_LENGTH,
    TRUNCATED,
    FRAGMENTED,
    BAD_IP_CHECKSUM,
    NOT_UDP,
    NOT_DNS_PORT,
    NOT_TARGET_ADDRESS,
    BAD_UDP_LENGTH,
    BAD_UDP_CHECKSUM,
    MALFORMED_DNS,
    NOT_A_QUERY,
    NO_QUESTION
}

/** An IPv4 packet parsed from a tunnel read. Holds a view over [bytes]; no payload copy is made. */
class Ipv4Packet internal constructor(
    val bytes: ByteArray,
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val sourceAddress: Int,
    val destinationAddress: Int,
    private val flagsAndFragmentOffset: Int
) {
    val payloadOffset: Int get() = headerLength

    val payloadLength: Int get() = totalLength - headerLength

    /** True when this packet is a fragment (MF flag set or a non-zero fragment offset). */
    val isFragmented: Boolean get() = (flagsAndFragmentOffset and 0x3FFF) != 0

    val sourceAddressText: String get() = Bytes.addressText(sourceAddress)

    val destinationAddressText: String get() = Bytes.addressText(destinationAddress)

    fun copyPayload(): ByteArray = bytes.copyOfRange(payloadOffset, totalLength)
}

sealed interface Ipv4ParseOutcome {
    data class Ok(val packet: Ipv4Packet) : Ipv4ParseOutcome
    data class Drop(val reason: PacketDropReason) : Ipv4ParseOutcome
}

/** Strict, defensive IPv4 parser: anything it cannot fully validate is dropped, never guessed. */
object Ipv4Parser {

    private const val MIN_HEADER_LENGTH = 20

    fun parse(bytes: ByteArray, length: Int): Ipv4ParseOutcome {
        if (length < MIN_HEADER_LENGTH || bytes.size < length) {
            return Ipv4ParseOutcome.Drop(PacketDropReason.TOO_SHORT)
        }
        val version = (bytes[0].toInt() and 0xF0) ushr 4
        if (version != 4) {
            return Ipv4ParseOutcome.Drop(PacketDropReason.NOT_IPV4)
        }
        val headerLength = (bytes[0].toInt() and 0x0F) * 4
        if (headerLength < MIN_HEADER_LENGTH || headerLength > length) {
            return Ipv4ParseOutcome.Drop(PacketDropReason.BAD_HEADER_LENGTH)
        }
        val totalLength = Bytes.readUInt16(bytes, 2)
        if (totalLength < headerLength) {
            return Ipv4ParseOutcome.Drop(PacketDropReason.BAD_TOTAL_LENGTH)
        }
        if (totalLength > length) {
            // The read was shorter than the header claims; the datagram is not complete.
            return Ipv4ParseOutcome.Drop(PacketDropReason.TRUNCATED)
        }
        if (!Checksums.verifyIpv4Header(bytes, 0, headerLength)) {
            return Ipv4ParseOutcome.Drop(PacketDropReason.BAD_IP_CHECKSUM)
        }

        val packet = Ipv4Packet(
            bytes = bytes,
            headerLength = headerLength,
            totalLength = totalLength,
            protocol = bytes[9].toInt() and 0xFF,
            sourceAddress = Bytes.readInt32(bytes, 12),
            destinationAddress = Bytes.readInt32(bytes, 16),
            flagsAndFragmentOffset = Bytes.readUInt16(bytes, 6)
        )
        if (packet.isFragmented) {
            return Ipv4ParseOutcome.Drop(PacketDropReason.FRAGMENTED)
        }
        return Ipv4ParseOutcome.Ok(packet)
    }

    /** Builds an IPv4 header for [payloadLength] bytes; the checksum field is filled in. */
    fun buildHeader(
        sourceAddress: Int,
        destinationAddress: Int,
        protocol: Int,
        payloadLength: Int,
        identification: Int
    ): ByteArray {
        val header = ByteArray(MIN_HEADER_LENGTH)
        header[0] = (0x45).toByte()
        header[1] = 0
        Bytes.writeUInt16(header, 2, MIN_HEADER_LENGTH + payloadLength)
        Bytes.writeUInt16(header, 4, identification)
        Bytes.writeUInt16(header, 6, 0)
        header[8] = 64
        header[9] = protocol.toByte()
        Bytes.writeInt32(header, 12, sourceAddress)
        Bytes.writeInt32(header, 16, destinationAddress)
        Bytes.writeUInt16(header, 10, 0)
        Bytes.writeUInt16(header, 10, Checksums.computeIpv4HeaderChecksum(header))
        return header
    }
}
