package tw.kuies.block.vpn

import tw.kuies.block.dns.packet.PacketDropReason

/**
 * Pure formatter for the debug-only tunnel drop diagnostic.
 *
 * It reports the IP version, the protocol/next-header number, and the destination port — never an
 * address or any payload — so a debug log built from it is safe to share. It exists to answer a
 * single open question: is a dropped packet a DNS-over-TCP retry to port 53, or traffic to another
 * port (for example 853)? Extracted from the service so the byte-offset logic is unit testable.
 */
object DropDiagnostics {

    /** Returns a one-line description, or null when the buffer is too short to describe. */
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

    private fun describeIpv4(reason: PacketDropReason, bytes: ByteArray, length: Int): String? {
        if (length < MIN_IPV4_HEADER_LENGTH) return null
        val protocol = bytes[9].toInt() and 0xFF
        val internetHeaderLength = (bytes[0].toInt() and 0x0F) * 4
        val port = destinationPort(bytes, length, internetHeaderLength)
        return "tunnelDrop ipv4 proto=$protocol dstPort=$port reason=$reason"
    }

    /** The destination port of the transport header at [transportOffset], or -1 when unavailable. */
    fun destinationPort(bytes: ByteArray, length: Int, transportOffset: Int): Int {
        if (transportOffset < MIN_IPV4_HEADER_LENGTH || transportOffset + 3 >= length) return -1
        return ((bytes[transportOffset + 2].toInt() and 0xFF) shl 8) or
            (bytes[transportOffset + 3].toInt() and 0xFF)
    }

    private const val MIN_IPV4_HEADER_LENGTH = 20
    private const val IPV6_HEADER_LENGTH = 40
}
