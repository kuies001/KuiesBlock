package tw.kuies.block.dns.message

import java.io.ByteArrayOutputStream

/** DNS response codes we care about. */
enum class DnsRcode(val code: Int) {
    NOERROR(0),
    FORMERR(1),
    SERVFAIL(2),
    NXDOMAIN(3),
    NOTIMP(4),
    REFUSED(5),
    OTHER(-1);

    companion object {
        fun fromCode(code: Int): DnsRcode =
            entries.firstOrNull { it != OTHER && it.code == code } ?: OTHER
    }
}

/** Reason a DNS message could not be trusted. Never contains query content. */
enum class DnsMessageError {
    TOO_SHORT,
    NOT_A_QUERY,
    NOT_A_RESPONSE,
    UNSUPPORTED_OPCODE,
    NO_QUESTION,
    MALFORMED
}

/** Parsed DNS header with the flag helpers the pipeline needs. */
data class DnsHeader(
    val id: Int,
    val flags: Int,
    val questionCount: Int,
    val answerCount: Int,
    val authorityCount: Int,
    val additionalCount: Int
) {
    val isResponse: Boolean get() = (flags and 0x8000) != 0
    val isTruncated: Boolean get() = (flags and 0x0200) != 0
    val opcode: Int get() = (flags shr 11) and 0x0F
    val rcode: Int get() = flags and 0x0F
}

data class DnsQuestion(val name: String, val type: Int, val dnsClass: Int)

/** A DNS query read from the tunnel, with enough context to build the reply. */
data class ParsedDnsQuery(
    val header: DnsHeader,
    val question: DnsQuestion,
    val questionEndOffset: Int,
    val ednsUdpPayloadSize: Int?
)

data class ParsedDnsResponse(val header: DnsHeader, val rcode: DnsRcode, val answerCount: Int)

sealed interface DnsQueryOutcome {
    data class Ok(val query: ParsedDnsQuery) : DnsQueryOutcome
    data class Invalid(val error: DnsMessageError) : DnsQueryOutcome
}

sealed interface DnsResponseOutcome {
    data class Ok(val response: ParsedDnsResponse) : DnsResponseOutcome
    data class Invalid(val error: DnsMessageError) : DnsResponseOutcome
}

/**
 * Domain-name codec. Reading follows compression pointers but refuses forward pointers, more
 * than [MAX_POINTER_HOPS] hops, or names longer than 255 bytes, so a hostile packet cannot spin
 * the parser or blow up memory.
 */
object DnsName {

    const val MAX_NAME_LENGTH = 255
    private const val MAX_POINTER_HOPS = 32

    data class ReadResult(val name: String, val nextOffset: Int)

    fun read(bytes: ByteArray, startOffset: Int, limit: Int): ReadResult? {
        val labels = ArrayList<String>(4)
        var offset = startOffset
        var nextOffset = -1
        var hops = 0
        var totalLength = 1
        while (true) {
            if (offset >= limit || offset < 0) return null
            val lengthByte = bytes[offset].toInt() and 0xFF
            when {
                lengthByte == 0 -> {
                    if (nextOffset < 0) nextOffset = offset + 1
                    break
                }
                lengthByte and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= limit) return null
                    val pointer = ((lengthByte and 0x3F) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                    if (nextOffset < 0) nextOffset = offset + 2
                    hops++
                    // Only backwards pointers are legal, which also makes loops impossible.
                    if (hops > MAX_POINTER_HOPS || pointer >= offset) return null
                    offset = pointer
                }
                lengthByte and 0xC0 != 0 -> return null
                else -> {
                    if (lengthByte > 63) return null
                    if (offset + 1 + lengthByte > limit) return null
                    totalLength += lengthByte + 1
                    if (totalLength > MAX_NAME_LENGTH) return null
                    labels.add(String(bytes, offset + 1, lengthByte, Charsets.US_ASCII))
                    offset += 1 + lengthByte
                }
            }
        }
        return ReadResult(labels.joinToString("."), nextOffset)
    }

    /** Writes [name] as a sequence of length-prefixed labels (no compression). */
    fun write(name: String, out: ByteArrayOutputStream) {
        val trimmed = name.trim().trimEnd('.')
        if (trimmed.isNotEmpty()) {
            for (label in trimmed.split('.')) {
                if (label.isEmpty()) continue
                val labelBytes = label.toByteArray(Charsets.US_ASCII)
                require(labelBytes.size <= 63) { "DNS label too long" }
                out.write(labelBytes.size)
                out.write(labelBytes)
            }
        }
        out.write(0)
    }
}

object DnsMessageReader {

    const val HEADER_SIZE = 12
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41
    const val CLASS_IN = 1

    fun readQuery(bytes: ByteArray, length: Int = bytes.size): DnsQueryOutcome {
        if (length < HEADER_SIZE) return DnsQueryOutcome.Invalid(DnsMessageError.TOO_SHORT)
        val header = readHeader(bytes)
        if (header.isResponse) return DnsQueryOutcome.Invalid(DnsMessageError.NOT_A_QUERY)
        if (header.opcode != 0) return DnsQueryOutcome.Invalid(DnsMessageError.UNSUPPORTED_OPCODE)
        if (header.questionCount < 1) return DnsQueryOutcome.Invalid(DnsMessageError.NO_QUESTION)

        val name = DnsName.read(bytes, HEADER_SIZE, length)
            ?: return DnsQueryOutcome.Invalid(DnsMessageError.MALFORMED)
        if (name.nextOffset + 4 > length) {
            return DnsQueryOutcome.Invalid(DnsMessageError.MALFORMED)
        }
        val question = DnsQuestion(
            name = name.name,
            type = readUInt16(bytes, name.nextOffset),
            dnsClass = readUInt16(bytes, name.nextOffset + 2)
        )
        val questionEnd = name.nextOffset + 4

        return DnsQueryOutcome.Ok(
            ParsedDnsQuery(
                header = header,
                question = question,
                questionEndOffset = questionEnd,
                ednsUdpPayloadSize = scanEdnsUdpPayloadSize(bytes, length, header, questionEnd)
            )
        )
    }

    fun readResponse(bytes: ByteArray, length: Int = bytes.size): DnsResponseOutcome {
        if (length < HEADER_SIZE) return DnsResponseOutcome.Invalid(DnsMessageError.TOO_SHORT)
        val header = readHeader(bytes)
        if (!header.isResponse) return DnsResponseOutcome.Invalid(DnsMessageError.NOT_A_RESPONSE)
        if (header.opcode != 0) return DnsResponseOutcome.Invalid(DnsMessageError.UNSUPPORTED_OPCODE)
        if (header.questionCount < 1) return DnsResponseOutcome.Invalid(DnsMessageError.NO_QUESTION)

        var offset = HEADER_SIZE
        repeat(header.questionCount) {
            val name = DnsName.read(bytes, offset, length)
                ?: return DnsResponseOutcome.Invalid(DnsMessageError.MALFORMED)
            offset = name.nextOffset + 4
            if (offset > length) {
                return DnsResponseOutcome.Invalid(DnsMessageError.MALFORMED)
            }
        }
        val recordCount = header.answerCount + header.authorityCount + header.additionalCount
        repeat(recordCount) {
            val next = skipRecord(bytes, offset, length)
                ?: return DnsResponseOutcome.Invalid(DnsMessageError.MALFORMED)
            offset = next
        }

        return DnsResponseOutcome.Ok(
            ParsedDnsResponse(
                header = header,
                rcode = DnsRcode.fromCode(header.rcode),
                answerCount = header.answerCount
            )
        )
    }

    /** Best-effort EDNS(0) UDP payload size from the additional section; null when absent. */
    private fun scanEdnsUdpPayloadSize(
        bytes: ByteArray,
        length: Int,
        header: DnsHeader,
        questionEnd: Int
    ): Int? {
        var offset = questionEnd
        repeat(header.answerCount + header.authorityCount) {
            offset = skipRecord(bytes, offset, length) ?: return null
        }
        repeat(header.additionalCount) {
            val name = DnsName.read(bytes, offset, length) ?: return null
            val recordStart = name.nextOffset
            if (recordStart + 10 > length) return null
            val type = readUInt16(bytes, recordStart)
            val klass = readUInt16(bytes, recordStart + 2)
            val rdLength = readUInt16(bytes, recordStart + 8)
            if (type == TYPE_OPT) return klass
            offset = recordStart + 10 + rdLength
            if (offset > length) return null
        }
        return null
    }

    private fun skipRecord(bytes: ByteArray, offset: Int, limit: Int): Int? {
        val name = DnsName.read(bytes, offset, limit) ?: return null
        val recordStart = name.nextOffset
        if (recordStart + 10 > limit) return null
        val rdLength = readUInt16(bytes, recordStart + 8)
        val end = recordStart + 10 + rdLength
        return if (end > limit) null else end
    }

    private fun readHeader(bytes: ByteArray): DnsHeader = DnsHeader(
        id = readUInt16(bytes, 0),
        flags = readUInt16(bytes, 2),
        questionCount = readUInt16(bytes, 4),
        answerCount = readUInt16(bytes, 6),
        authorityCount = readUInt16(bytes, 8),
        additionalCount = readUInt16(bytes, 10)
    )

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

object DnsMessageWriter {

    private const val HEADER_SIZE = DnsMessageReader.HEADER_SIZE

    /** Echoes a query's id/question as a response with no answers; used for SERVFAIL and truncation. */
    fun responseHeaderOnly(
        queryBytes: ByteArray,
        queryLength: Int,
        rcode: DnsRcode,
        truncated: Boolean
    ): ByteArray {
        val query = DnsMessageReader.readQuery(queryBytes, queryLength)
        val questionEnd = (query as? DnsQueryOutcome.Ok)?.query?.questionEndOffset ?: HEADER_SIZE
        val queryFlags = if (queryLength >= 2) {
            ((queryBytes[2].toInt() and 0xFF) shl 8) or (queryBytes[3].toInt() and 0xFF)
        } else {
            0
        }
        val opcode = (queryFlags shr 11) and 0x0F
        val recursionDesired = (queryFlags shr 8) and 0x01
        var flags = 0x8000 or (opcode shl 11) or (recursionDesired shl 8) or 0x0080
        if (truncated) flags = flags or 0x0200
        flags = flags or (rcode.code and 0x0F)

        val out = ByteArrayOutputStream(HEADER_SIZE + (questionEnd - HEADER_SIZE).coerceAtLeast(0))
        writeUInt16(out, readUInt16(queryBytes, 0))
        writeUInt16(out, flags)
        writeUInt16(out, 1)
        writeUInt16(out, 0)
        writeUInt16(out, 0)
        writeUInt16(out, 0)
        if (questionEnd > HEADER_SIZE && questionEnd <= queryLength) {
            out.write(queryBytes, HEADER_SIZE, questionEnd - HEADER_SIZE)
        }
        return out.toByteArray()
    }

    fun servfail(queryBytes: ByteArray, queryLength: Int): ByteArray =
        responseHeaderOnly(queryBytes, queryLength, DnsRcode.SERVFAIL, truncated = false)

    fun truncated(queryBytes: ByteArray, queryLength: Int): ByteArray =
        responseHeaderOnly(queryBytes, queryLength, DnsRcode.NOERROR, truncated = true)

    /** Builds a recursive query, optionally advertising an EDNS(0) UDP payload size. */
    fun buildQuery(
        transactionId: Int,
        name: String,
        type: Int,
        ednsUdpPayloadSize: Int? = null
    ): ByteArray {
        val out = ByteArrayOutputStream(64)
        writeUInt16(out, transactionId and 0xFFFF)
        writeUInt16(out, 0x0100)
        writeUInt16(out, 1)
        writeUInt16(out, 0)
        writeUInt16(out, 0)
        writeUInt16(out, if (ednsUdpPayloadSize != null) 1 else 0)
        DnsName.write(name, out)
        writeUInt16(out, type)
        writeUInt16(out, DnsMessageReader.CLASS_IN)
        if (ednsUdpPayloadSize != null) {
            out.write(0)
            writeUInt16(out, DnsMessageReader.TYPE_OPT)
            writeUInt16(out, ednsUdpPayloadSize)
            writeUInt16(out, 0)
            writeUInt16(out, 0)
            writeUInt16(out, 0)
        }
        return out.toByteArray()
    }

    private fun writeUInt16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
