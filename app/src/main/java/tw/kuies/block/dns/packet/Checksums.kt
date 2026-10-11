package tw.kuies.block.dns.packet

/**
 * Internet checksum helpers (RFC 1071).
 *
 * All sums are computed over 16-bit big-endian words. Callers must zero the checksum field
 * before computing it, and may verify a packet by summing it checksum-included and expecting
 * the folded result to be `0xFFFF`.
 */
object Checksums {

    private const val IP_PROTOCOL_UDP = 17

    /** One's-complement sum of [length] bytes starting at [offset], with a trailing odd byte padded. */
    fun onesComplementSum(data: ByteArray, offset: Int, length: Int, initial: Int = 0): Int {
        var sum = initial
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) {
            sum += (data[index].toInt() and 0xFF) shl 8
        }
        return sum
    }

    /** Folds carries back into the low 16 bits. */
    fun fold(sum: Int): Int {
        var folded = sum
        while (folded ushr 16 != 0) {
            folded = (folded and 0xFFFF) + (folded ushr 16)
        }
        return folded
    }

    /** Finalizes a sum into a checksum value; the checksum field must have been zero. */
    fun finish(sum: Int): Int = fold(sum).inv() and 0xFFFF

    /** Checksum for an IPv4 header whose checksum field is currently zero. */
    fun computeIpv4HeaderChecksum(header: ByteArray, offset: Int = 0, length: Int = header.size): Int =
        finish(onesComplementSum(header, offset, length))

    /** True when an IPv4 header validates (summing it checksum-included folds to `0xFFFF`). */
    fun verifyIpv4Header(header: ByteArray, offset: Int = 0, length: Int = header.size): Boolean =
        fold(onesComplementSum(header, offset, length)) == 0xFFFF

    /** UDP checksum for a segment whose checksum field is currently zero. */
    fun computeUdpChecksum(
        sourceAddress: Int,
        destinationAddress: Int,
        segment: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        val sum = pseudoHeaderSum(sourceAddress, destinationAddress, length) +
            onesComplementSum(segment, offset, length)
        return finish(sum)
    }

    /** True when a UDP segment validates against its pseudo-header (checksum field included). */
    fun verifyUdpChecksum(
        sourceAddress: Int,
        destinationAddress: Int,
        segment: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        val sum = pseudoHeaderSum(sourceAddress, destinationAddress, length) +
            onesComplementSum(segment, offset, length)
        return fold(sum) == 0xFFFF
    }

    private fun pseudoHeaderSum(sourceAddress: Int, destinationAddress: Int, udpLength: Int): Int {
        var sum = ((sourceAddress ushr 16) and 0xFFFF) + (sourceAddress and 0xFFFF)
        sum += ((destinationAddress ushr 16) and 0xFFFF) + (destinationAddress and 0xFFFF)
        sum += IP_PROTOCOL_UDP
        sum += udpLength
        return sum
    }
}
