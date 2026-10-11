package tw.kuies.block.vpn

import tw.kuies.block.dns.packet.PacketDropReason

/**
 * Pure formatters for the debug-only tunnel drop diagnostic.
 *
 * Two forms exist on purpose:
 *  * [describe] is the coarse form — IP version, protocol, destination port — with no addresses.
 *  * [describeWithEndpoints] adds the source and destination addresses (and the TCP flags). It is a
 *    **temporary investigation aid** used only while the Private DNS route question is open, and it
 *    is only ever reached in debuggable builds. It never logs a payload or DNS query content.
 *
 * Extracted from the service so the byte-offset logic is unit testable.
 */
object DropDiagnostics {

    /** Coarse, address-free description; null when the buffer is too short to describe. */
    fun describe(reason: PacketDropReason, bytes: ByteArray, length: Int): String? {
        if (length <= 0 || bytes.size < length) return null
        val version = (bytes[0].toInt() and 0xF0) ushr 4
        return when (version) {
            4 -> describeIpv4(reason, bytes, length)
            6 -> if (length < IPV6_HEADER_LENGTH) {
                "tunnelDrop ipv6 reason=$reason"
            } else {
                "tunnelDrop ipv6 nextHeader=${bytes[6].toInt() and 0xFF} reason=$reason"
            }
            else -> "tunnelDrop ipv=$version reason=$reason"
        }
    }

    /**
     * Temporary debug form with endpoints. Reveals where a dropped packet was going, which is the
     * only way to tell whether port 853 traffic targets our virtual DNS or a public address.
     */
    fun describeWithEndpoints(reason: PacketDropReason, bytes: ByteArray, length: Int): String? {
        if (length <= 0 || bytes.size < length) return null
        val version = (bytes[0].toInt() and 0xF0) ushr 4
        return when (version) {
            4 -> describeIpv4WithEndpoints(reason, bytes, length)
            6 -> if (length < IPV6_HEADER_LENGTH) {
                "tunnelDrop ipv6 reason=$reason"
            } else {
                val nextHeader = bytes[6].toInt() and 0xFF
                "tunnelDrop ipv6 nextHeader=$nextHeader " +
                    "src=${formatIpv6(bytes, 8)} dst=${formatIpv6(bytes, 24)} reason=$reason"
            }
            else -> "tunnelDrop ipv=$version reason=$reason"
        }
    }

    private fun describeIpv4(reason: PacketDropReason, bytes: ByteArray, length: Int): String? {
        if (length < MIN_IPV4_HEADER_LENGTH) return null
        val protocol = bytes[9].toInt() and 0xFF
        val internetHeaderLength = (bytes[0].toInt() and 0x0F) * 4
        val port = destinationPort(bytes, length, internetHeaderLength)
        return "tunnelDrop ipv4 proto=$protocol dstPort=$port reason=$reason"
    }

    private fun describeIpv4WithEndpoints(reason: PacketDropReason, bytes: ByteArray, length: Int): String? {
        if (length < MIN_IPV4_HEADER_LENGTH) return null
        val protocol = bytes[9].toInt() and 0xFF
        val internetHeaderLength = (bytes[0].toInt() and 0x0F) * 4
        val port = destinationPort(bytes, length, internetHeaderLength)
        val flags = if (protocol == IP_PROTOCOL_TCP) tcpFlags(bytes, length, internetHeaderLength) else "-"
        return "tunnelDrop ipv4 proto=$protocol dstPort=$port " +
            "src=${formatIpv4(bytes, 12)} dst=${formatIpv4(bytes, 16)} flags=$flags reason=$reason"
    }

    /** The destination port of the transport header at [transportOffset], or -1 when unavailable. */
    fun destinationPort(bytes: ByteArray, length: Int, transportOffset: Int): Int {
        if (transportOffset < MIN_IPV4_HEADER_LENGTH || transportOffset + 3 >= length) return -1
        return ((bytes[transportOffset + 2].toInt() and 0xFF) shl 8) or
            (bytes[transportOffset + 3].toInt() and 0xFF)
    }

    /** Compact TCP flag names; "-" when the flags byte is not captured. */
    fun tcpFlags(bytes: ByteArray, length: Int, transportOffset: Int): String {
        val flagsIndex = transportOffset + FLAGS_OFFSET_IN_TCP_HEADER
        if (flagsIndex < 0 || flagsIndex >= length || flagsIndex >= bytes.size) return "-"
        val flags = bytes[flagsIndex].toInt() and 0xFF
        if (flags == 0) return "-"
        val names = buildList {
            if (flags and 0x02 != 0) add("SYN")
            if (flags and 0x10 != 0) add("ACK")
            if (flags and 0x01 != 0) add("FIN")
            if (flags and 0x04 != 0) add("RST")
            if (flags and 0x08 != 0) add("PSH")
        }
        return if (names.isEmpty()) "0x${flags.toString(16)}" else names.joinToString("+")
    }

    fun formatIpv4(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset + 3 >= bytes.size) return "-"
        return "${bytes[offset].toInt() and 0xFF}." +
            "${bytes[offset + 1].toInt() and 0xFF}." +
            "${bytes[offset + 2].toInt() and 0xFF}." +
            "${bytes[offset + 3].toInt() and 0xFF}"
    }

    fun formatIpv6(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset + 15 >= bytes.size) return "-"
        return (0 until 8).joinToString(":") { group ->
            val index = offset + group * 2
            (((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)).toString(16)
        }
    }

    private const val MIN_IPV4_HEADER_LENGTH = 20
    private const val IPV6_HEADER_LENGTH = 40
    private const val IP_PROTOCOL_TCP = 6
    private const val FLAGS_OFFSET_IN_TCP_HEADER = 13
}
