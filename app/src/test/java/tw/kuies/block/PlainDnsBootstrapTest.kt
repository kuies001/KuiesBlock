package tw.kuies.block

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsQueryOutcome
import tw.kuies.block.doh.PlainDnsBootstrap
import tw.kuies.block.doh.VpnSocketProtector

class PlainDnsBootstrapTest {

    /** A loopback UDP DNS stub whose replies come from [handler]. */
    private class UdpDnsStub(private val handler: (ByteArray) -> ByteArray?) {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val address: InetSocketAddress =
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), socket.localPort)

        init {
            thread(isDaemon = true) {
                val buffer = ByteArray(1500)
                while (!socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val response = handler(buffer.copyOf(packet.length))
                        if (response != null) {
                            socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                        }
                    } catch (ignored: Exception) {
                        return@thread
                    }
                }
            }
        }

        fun close() {
            socket.close()
        }
    }

    private fun aRecordResponse(queryBytes: ByteArray, addressText: String): ByteArray {
        val questionEnd = (DnsMessageReader.readQuery(queryBytes) as DnsQueryOutcome.Ok)
            .query.questionEndOffset
        val address = ipAddress(addressText)
        val out = ByteArrayOutputStream()
        out.write(queryBytes[0].toInt())
        out.write(queryBytes[1].toInt())
        out.write(0x81)
        out.write(0x80)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(queryBytes, 12, questionEnd - 12)
        out.write(0xC0)
        out.write(0x0C)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x01)
        out.write(byteArrayOf(0, 0, 0, 60))
        out.write(0x00)
        out.write(0x04)
        out.write((address ushr 24) and 0xFF)
        out.write((address ushr 16) and 0xFF)
        out.write((address ushr 8) and 0xFF)
        out.write(address and 0xFF)
        return out.toByteArray()
    }

    @Test
    fun resolvesTheDohHostnameThroughTheUnderlyingServer() {
        val stub = UdpDnsStub { aRecordResponse(it, "203.0.113.7") }
        try {
            val bootstrap = PlainDnsBootstrap(
                protector = VpnSocketProtector.NONE,
                servers = listOf(stub.address)
            )
            val addresses = bootstrap.resolve("dns.kuies.tw")
            assertEquals(listOf("203.0.113.7"), addresses.map { it.hostAddress })
        } finally {
            stub.close()
        }
    }

    @Test
    fun protectsItsBootstrapSocket() {
        val stub = UdpDnsStub { aRecordResponse(it, "203.0.113.9") }
        var datagramProtections = 0
        val protector = object : VpnSocketProtector {
            override fun protect(socket: java.net.Socket): Boolean = false
            override fun protect(socket: DatagramSocket): Boolean {
                datagramProtections++
                return true
            }
        }
        try {
            val bootstrap = PlainDnsBootstrap(protector, listOf(stub.address))
            bootstrap.resolve("dns.kuies.tw")
            assertTrue(datagramProtections >= 1)
        } finally {
            stub.close()
        }
    }

    @Test
    fun ignoresAReplyWithTheWrongTransactionId() {
        val stub = UdpDnsStub { query ->
            val response = aRecordResponse(query, "203.0.113.8")
            // Corrupt the echoed transaction id.
            response[0] = (response[0].toInt() xor 0x01).toByte()
            response
        }
        try {
            val bootstrap = PlainDnsBootstrap(
                protector = VpnSocketProtector.NONE,
                servers = listOf(stub.address),
                timeoutMillis = 300
            )
            assertTrue(bootstrap.resolve("dns.kuies.tw").isEmpty())
        } finally {
            stub.close()
        }
    }

    @Test
    fun ignoresAReplyThatIsNotADnsResponse() {
        val stub = UdpDnsStub { byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13) }
        try {
            val bootstrap = PlainDnsBootstrap(
                protector = VpnSocketProtector.NONE,
                servers = listOf(stub.address),
                timeoutMillis = 300
            )
            assertTrue(bootstrap.resolve("dns.kuies.tw").isEmpty())
        } finally {
            stub.close()
        }
    }

    @Test
    fun timesOutToAnEmptyListWhenNothingAnswers() {
        val stub = UdpDnsStub { null }
        try {
            val bootstrap = PlainDnsBootstrap(
                protector = VpnSocketProtector.NONE,
                servers = listOf(stub.address),
                timeoutMillis = 200
            )
            assertTrue(bootstrap.resolve("dns.kuies.tw").isEmpty())
        } finally {
            stub.close()
        }
    }

    @Test
    fun returnsEmptyWhenNoUnderlyingServerIsKnown() {
        val bootstrap = PlainDnsBootstrap(VpnSocketProtector.NONE, emptyList())
        assertTrue(bootstrap.resolve("dns.kuies.tw").isEmpty())
    }
}
