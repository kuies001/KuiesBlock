package tw.kuies.block

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsMessageWriter
import tw.kuies.block.dns.message.DnsRcode
import tw.kuies.block.doh.DohEndpoint
import tw.kuies.block.doh.DohFailure
import tw.kuies.block.doh.DohResult
import tw.kuies.block.doh.HostnameResolver
import tw.kuies.block.doh.OkHttpDohTransport
import tw.kuies.block.doh.VpnSocketProtector

class OkHttpDohTransportTest {

    private val servers = mutableListOf<MockWebServer>()
    private val transports = mutableListOf<OkHttpDohTransport>()

    /** Never touch the system resolver: the DoH hostname always resolves to loopback here. */
    private val loopback = HostnameResolver { listOf(InetAddress.getByName("127.0.0.1")) }

    @After
    fun tearDown() {
        transports.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    private fun startServer(): MockWebServer {
        val server = MockWebServer()
        server.start()
        servers.add(server)
        return server
    }

    private fun transportFor(server: MockWebServer, readTimeoutMillis: Long = 5_000): OkHttpDohTransport {
        val endpoint = DohEndpoint(
            url = server.url("/dns-query").toString(),
            host = "localhost",
            port = server.port
        )
        val transport = OkHttpDohTransport(
            endpoint = endpoint,
            protector = VpnSocketProtector.NONE,
            resolver = loopback,
            callTimeoutMillis = 4_000,
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = readTimeoutMillis
        )
        transports.add(transport)
        return transport
    }

    private fun query(id: Int): ByteArray =
        DnsMessageWriter.buildQuery(id, "ads.example.com", DnsMessageReader.TYPE_A, 1232)

    private fun responseEchoing(id: Int): ByteArray {
        val q = query(id)
        return DnsMessageWriter.responseHeaderOnly(q, q.size, DnsRcode.NOERROR, truncated = false)
    }

    private fun dnsMessageResponse(body: ByteArray): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/dns-message")
            .body(Buffer().write(body))
            .build()

    @Test
    fun returnsTheResponseOnSuccess() {
        val server = startServer()
        val body = responseEchoing(0x0102)
        server.enqueue(dnsMessageResponse(body))

        val result = transportFor(server).exchange(query(0x0102))
        assertTrue(result is DohResult.Success)
        assertEquals(body.size, (result as DohResult.Success).responseBytes.size)
    }

    @Test
    fun sendsAPostWithTheDnsMessageContentType() {
        val server = startServer()
        server.enqueue(dnsMessageResponse(responseEchoing(7)))

        transportFor(server).exchange(query(7))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("application/dns-message", recorded.headers["Content-Type"])
        assertEquals("application/dns-message", recorded.headers["Accept"])
    }

    @Test
    fun rejectsANonOkStatus() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Content-Type", "application/dns-message")
                .body(Buffer().write(responseEchoing(1)))
                .build()
        )
        val result = transportFor(server).exchange(query(1))
        assertEquals(DohFailure.HTTP_STATUS, (result as DohResult.Failure).failure)
        assertEquals(503, result.httpStatus)
    }

    @Test
    fun rejectsAWrongContentType() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<html></html>")
                .build()
        )
        val result = transportFor(server).exchange(query(1))
        assertEquals(DohFailure.CONTENT_TYPE, (result as DohResult.Failure).failure)
    }

    @Test
    fun rejectsAnEmptyBody() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/dns-message")
                .body(Buffer())
                .build()
        )
        val result = transportFor(server).exchange(query(1))
        assertEquals(DohFailure.EMPTY_BODY, (result as DohResult.Failure).failure)
    }

    @Test
    fun rejectsATransactionIdMismatch() {
        val server = startServer()
        server.enqueue(dnsMessageResponse(responseEchoing(0x9999)))
        val result = transportFor(server).exchange(query(0x1111))
        assertEquals(DohFailure.TRANSACTION_ID_MISMATCH, (result as DohResult.Failure).failure)
    }

    @Test
    fun rejectsAMalformedResponse() {
        val server = startServer()
        server.enqueue(dnsMessageResponse("not-a-dns-message".toByteArray()))
        val result = transportFor(server).exchange(query(1))
        assertEquals(DohFailure.MALFORMED_RESPONSE, (result as DohResult.Failure).failure)
    }

    @Test
    fun rejectsAnOversizedBody() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/dns-message")
                .body(Buffer().write(ByteArray(70_000)))
                .build()
        )
        val result = transportFor(server).exchange(query(1))
        assertEquals(DohFailure.TOO_LARGE, (result as DohResult.Failure).failure)
    }

    @Test
    fun reportsATimeout() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/dns-message")
                .bodyDelay(3, TimeUnit.SECONDS)
                .body(Buffer().write(responseEchoing(5)))
                .build()
        )
        val result = transportFor(server, readTimeoutMillis = 250).exchange(query(5))
        assertEquals(DohFailure.TIMEOUT, (result as DohResult.Failure).failure)
    }

    @Test
    fun reportsAnUntrustedTlsCertificate() {
        val server = MockWebServer()
        // A certificate the client's default trust store will (correctly) refuse.
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
        servers.add(server)
        server.enqueue(dnsMessageResponse(responseEchoing(3)))

        val endpoint = DohEndpoint(
            url = server.url("/dns-query").toString(),
            host = "localhost",
            port = server.port
        )
        val transport = OkHttpDohTransport(
            endpoint = endpoint,
            protector = VpnSocketProtector.NONE,
            resolver = loopback
        )
        transports.add(transport)

        val result = transport.exchange(query(3))
        assertEquals(DohFailure.TLS_FAILED, (result as DohResult.Failure).failure)
    }

    @Test
    fun protectsEverySocketItCreates() {
        val server = startServer()
        server.enqueue(dnsMessageResponse(responseEchoing(11)))

        var protectedSockets = 0
        val protector = object : VpnSocketProtector {
            override fun protect(socket: java.net.Socket): Boolean {
                protectedSockets++
                return true
            }

            override fun protect(socket: java.net.DatagramSocket): Boolean = false
        }
        val transport = OkHttpDohTransport(
            endpoint = DohEndpoint(server.url("/dns-query").toString(), "localhost", server.port),
            protector = protector,
            resolver = loopback
        )
        transports.add(transport)

        assertTrue(transport.exchange(query(11)) is DohResult.Success)
        assertTrue("expected the upstream socket to be protected", protectedSockets >= 1)
    }

    @Test
    fun closeIsIdempotentAndSafe() {
        val server = startServer()
        server.enqueue(dnsMessageResponse(responseEchoing(42)))
        val transport = transportFor(server)

        assertTrue(transport.exchange(query(42)) is DohResult.Success)
        // Closing multiple times should not throw
        transport.close()
        transport.close()
    }
}
