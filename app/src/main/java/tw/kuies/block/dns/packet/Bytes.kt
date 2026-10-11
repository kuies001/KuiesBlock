package tw.kuies.block.dns.packet

/** Big-endian read/write helpers shared by the IPv4/UDP/DNS codecs. */
object Bytes {

    fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    fun writeInt32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    /** Renders a 32-bit address held as an int into dotted-quad form. */
    fun addressText(address: Int): String =
        "${(address ushr 24) and 0xFF}.${(address ushr 16) and 0xFF}.${(address ushr 8) and 0xFF}.${address and 0xFF}"

    /** Parses dotted-quad IPv4 text; returns null when the text is not a valid IPv4 literal. */
    fun addressFromText(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toInt()
            if (value !in 0..255) return null
            result = (result shl 8) or value
        }
        return result
    }
}
