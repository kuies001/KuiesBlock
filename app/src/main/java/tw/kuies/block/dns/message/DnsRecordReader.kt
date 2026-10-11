package tw.kuies.block.dns.message

import java.net.InetAddress

/** Extracts A / AAAA addresses from a DNS response. Used only to bootstrap the DoH hostname. */
object DnsRecordReader {

    private const val HEADER_SIZE = DnsMessageReader.HEADER_SIZE

    fun readAddressRecords(bytes: ByteArray, length: Int = bytes.size): List<InetAddress> {
        if (length < HEADER_SIZE) return emptyList()
        val questionCount = readUInt16(bytes, 4)
        val answerCount = readUInt16(bytes, 6)

        var offset = HEADER_SIZE
        repeat(questionCount) {
            val name = DnsName.read(bytes, offset, length) ?: return emptyList()
            offset = name.nextOffset + 4
            if (offset > length) return emptyList()
        }

        val addresses = ArrayList<InetAddress>(4)
        repeat(answerCount) {
            val name = DnsName.read(bytes, offset, length) ?: return addresses
            val recordStart = name.nextOffset
            if (recordStart + 10 > length) return addresses
            val type = readUInt16(bytes, recordStart)
            val rdLength = readUInt16(bytes, recordStart + 8)
            val rdataStart = recordStart + 10
            if (rdataStart + rdLength > length) return addresses
            when {
                type == DnsMessageReader.TYPE_A && rdLength == 4 ->
                    addresses.add(InetAddress.getByAddress(bytes.copyOfRange(rdataStart, rdataStart + 4)))
                type == DnsMessageReader.TYPE_AAAA && rdLength == 16 ->
                    addresses.add(InetAddress.getByAddress(bytes.copyOfRange(rdataStart, rdataStart + 16)))
            }
            offset = rdataStart + rdLength
        }
        return addresses
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
