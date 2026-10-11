package tw.kuies.block

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.message.DnsMessageError
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsMessageWriter
import tw.kuies.block.dns.message.DnsQueryOutcome
import tw.kuies.block.dns.message.DnsResponseOutcome
import tw.kuies.block.dns.message.DnsRcode

class DnsMessageTest {

    private fun u16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeQuestion(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(out, DnsMessageReader.TYPE_A)
        u16(out, DnsMessageReader.CLASS_IN)
    }

    /** A response whose answer name is a compression pointer back to the question name. */
    private fun responseWithCompressedAnswer(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        u16(out, id)
        u16(out, 0x8180)
        u16(out, 1)
        u16(out, 1)
        u16(out, 0)
        u16(out, 0)
        writeQuestion(out, name)
        u16(out, 0xC00C)
        u16(out, DnsMessageReader.TYPE_A)
        u16(out, DnsMessageReader.CLASS_IN)
        out.write(byteArrayOf(0, 0, 0, 60))
        u16(out, 4)
        out.write(byteArrayOf(1, 2, 3, 4))
        return out.toByteArray()
    }

    @Test
    fun readsABuiltQueryWithEdns() {
        val bytes = DnsMessageWriter.buildQuery(0x1234, "ads.example.com", DnsMessageReader.TYPE_A, 1232)
        val query = (DnsMessageReader.readQuery(bytes) as DnsQueryOutcome.Ok).query
        assertEquals(0x1234, query.header.id)
        assertEquals("ads.example.com", query.question.name)
        assertEquals(DnsMessageReader.TYPE_A, query.question.type)
        assertEquals(1232, query.ednsUdpPayloadSize)
    }

    @Test
    fun queryWithoutEdnsAdvertisesNoSize() {
        val bytes = DnsMessageWriter.buildQuery(1, "a.test", DnsMessageReader.TYPE_A)
        val query = (DnsMessageReader.readQuery(bytes) as DnsQueryOutcome.Ok).query
        assertNull(query.ednsUdpPayloadSize)
    }

    @Test
    fun rejectsAMessageTooShortToBeDns() {
        val outcome = DnsMessageReader.readQuery(ByteArray(5), 5)
        assertEquals(DnsMessageError.TOO_SHORT, (outcome as DnsQueryOutcome.Invalid).error)
    }

    @Test
    fun rejectsAResponseWhereAQueryIsExpected() {
        val bytes = DnsMessageWriter.buildQuery(1, "a.test", DnsMessageReader.TYPE_A)
        bytes[2] = (bytes[2].toInt() or 0x80).toByte()
        val outcome = DnsMessageReader.readQuery(bytes)
        assertEquals(DnsMessageError.NOT_A_QUERY, (outcome as DnsQueryOutcome.Invalid).error)
    }

    @Test
    fun rejectsAQueryWhereAResponseIsExpected() {
        val bytes = DnsMessageWriter.buildQuery(1, "a.test", DnsMessageReader.TYPE_A)
        val outcome = DnsMessageReader.readResponse(bytes)
        assertEquals(DnsMessageError.NOT_A_RESPONSE, (outcome as DnsResponseOutcome.Invalid).error)
    }

    @Test
    fun rejectsAnUnsupportedOpcode() {
        val bytes = DnsMessageWriter.buildQuery(1, "a.test", DnsMessageReader.TYPE_A)
        bytes[2] = (bytes[2].toInt() or 0x08).toByte()
        val outcome = DnsMessageReader.readQuery(bytes)
        assertEquals(DnsMessageError.UNSUPPORTED_OPCODE, (outcome as DnsQueryOutcome.Invalid).error)
    }

    @Test
    fun rejectsAQueryWithoutAQuestion() {
        val bytes = DnsMessageWriter.buildQuery(1, "a.test", DnsMessageReader.TYPE_A)
        bytes[4] = 0
        bytes[5] = 0
        val outcome = DnsMessageReader.readQuery(bytes)
        assertEquals(DnsMessageError.NO_QUESTION, (outcome as DnsQueryOutcome.Invalid).error)
    }

    @Test
    fun rejectsATruncatedName() {
        val bytes = DnsMessageWriter.buildQuery(1, "verylonglabel.test", DnsMessageReader.TYPE_A)
        // Claim a label longer than the remaining bytes.
        bytes[12] = 40
        val outcome = DnsMessageReader.readQuery(bytes)
        assertEquals(DnsMessageError.MALFORMED, (outcome as DnsQueryOutcome.Invalid).error)
    }

    @Test
    fun rejectsAForwardCompressionPointer() {
        val bytes = ByteArray(40)
        bytes[0] = 0
        bytes[1] = 1
        bytes[2] = 0x01
        bytes[3] = 0x00
        bytes[4] = 0
        bytes[5] = 1
        bytes[12] = 0xC0.toByte()
        bytes[13] = 32
        val outcome = DnsMessageReader.readQuery(bytes, bytes.size)
        assertEquals(DnsMessageError.MALFORMED, (outcome as DnsQueryOutcome.Invalid).error)
    }

    @Test
    fun rejectsASelfReferentialPointerLoop() {
        val bytes = ByteArray(40)
        bytes[1] = 1
        bytes[2] = 0x01
        bytes[4] = 0
        bytes[5] = 1
        bytes[12] = 0xC0.toByte()
        bytes[13] = 12
        val outcome = DnsMessageReader.readQuery(bytes, bytes.size)
        assertEquals(DnsMessageError.MALFORMED, (outcome as DnsQueryOutcome.Invalid).error)
    }

    @Test
    fun rejectsAnOverlongName() {
        val labels = (1..60).joinToString(".") { "label$it" }
        val bytes = DnsMessageWriter.buildQuery(1, labels, DnsMessageReader.TYPE_A)
        val outcome = DnsMessageReader.readQuery(bytes)
        assertEquals(DnsMessageError.MALFORMED, (outcome as DnsQueryOutcome.Invalid).error)
    }

    @Test
    fun readsAResponseWithACompressedAnswerName() {
        val bytes = responseWithCompressedAnswer(0x7777, "ads.example.com")
        val outcome = DnsMessageReader.readResponse(bytes, bytes.size)
        assertTrue(outcome is DnsResponseOutcome.Ok)
        val response = (outcome as DnsResponseOutcome.Ok).response
        assertEquals(0x7777, response.header.id)
        assertEquals(1, response.answerCount)
        assertEquals(DnsRcode.NOERROR, response.rcode)
    }

    @Test
    fun rejectsAResponseWithADanglingRecordLength() {
        val bytes = responseWithCompressedAnswer(1, "ads.example.com")
        // The record length sits two bytes before the four-byte rdata at the end.
        val rdLengthOffset = bytes.size - 6
        bytes[rdLengthOffset] = 0x00
        bytes[rdLengthOffset + 1] = 0xFF.toByte()
        val outcome = DnsMessageReader.readResponse(bytes, bytes.size)
        assertEquals(DnsMessageError.MALFORMED, (outcome as DnsResponseOutcome.Invalid).error)
    }

    @Test
    fun servfailEchoesIdAndQuestionAndDropsEdns() {
        val query = DnsMessageWriter.buildQuery(0xABCD, "blocked.example", DnsMessageReader.TYPE_A, 1232)
        val question = (DnsMessageReader.readQuery(query) as DnsQueryOutcome.Ok).query
        val response = DnsMessageWriter.servfail(query, query.size)

        // Header + question only: the OPT record was not echoed.
        assertEquals(question.questionEndOffset, response.size)
        val parsed = (DnsMessageReader.readResponse(response, response.size) as DnsResponseOutcome.Ok).response
        assertEquals(0xABCD, parsed.header.id)
        assertEquals(DnsRcode.SERVFAIL, parsed.rcode)
        assertEquals(0, parsed.answerCount)
    }

    @Test
    fun truncatedResponseSetsTheTcBit() {
        val query = DnsMessageWriter.buildQuery(9, "big.example", DnsMessageReader.TYPE_A, 4096)
        val response = DnsMessageWriter.truncated(query, query.size)
        val parsed = (DnsMessageReader.readResponse(response, response.size) as DnsResponseOutcome.Ok).response
        assertTrue(parsed.header.isTruncated)
        assertEquals(DnsRcode.NOERROR, parsed.rcode)
        assertEquals(9, parsed.header.id)
    }
}
