package tw.kuies.block

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.Source
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.BodyRead
import tw.kuies.block.filter.FetchLimits
import tw.kuies.block.filter.SubscriptionBodyReader
import tw.kuies.block.filter.SubscriptionFailure
import tw.kuies.block.filter.SubscriptionFetchRequest
import tw.kuies.block.filter.SubscriptionFetchResult
import tw.kuies.block.filter.SubscriptionFetcher
import tw.kuies.block.filter.SubscriptionHostGuard
import tw.kuies.block.filter.SubscriptionOrigin

/**
 * The download path against a real HTTPS server.
 *
 * The server is reached over loopback while the *policy* is given a resolver that reports the harness
 * host as public, so the address policy stays at its real, strict setting in every test instead of
 * being relaxed for the harness. Anything that must never leave the process is asserted by counting
 * what the server actually received.
 */
class SubscriptionFetcherTest {

    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<OkHttpClient>()

    /** The certificate the current server was started with; the harness client trusts exactly it. */
    private var heldCertificate: HeldCertificate? = null

    private val testHost = "filters.test"
    private val publicAddress = InetAddress.getByName("203.0.113.10")

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.connectionPool.evictAll() } }
        servers.forEach { runCatching { it.close() } }
    }

    /** Only the harness host resolves, and only to a public literal. */
    private fun guard() = SubscriptionHostGuard { host ->
        if (host == testHost) listOf(publicAddress) else throw UnknownHostException(host)
    }

    private fun startServer(): MockWebServer {
        val certificate = HeldCertificate.Builder()
            .commonName(testHost)
            .addSubjectAlternativeName(testHost)
            .build()
        heldCertificate = certificate
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
        servers.add(server)
        return server
    }

    /** Trusts the harness certificate and nothing else; TLS validation itself stays on. */
    private fun newClient(followRedirects: Boolean = false): OkHttpClient {
        val trusted = requireNotNull(heldCertificate) { "startServer() must run first" }
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(trusted.certificate)
            .build()
        val client = OkHttpClient.Builder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .build()
        clients.add(client)
        return client
    }

    private fun fetcher(
        limits: FetchLimits = FetchLimits.DEFAULT,
        clock: () -> Long = System::currentTimeMillis,
        followRedirects: Boolean = false
    ): SubscriptionFetcher = SubscriptionFetcher(
        guard = guard(),
        client = newClient(followRedirects),
        limits = limits,
        clock = clock
    )

    private fun request(
        server: MockWebServer,
        path: String = "/list.txt",
        origin: SubscriptionOrigin = SubscriptionOrigin.USER,
        etag: String? = null,
        allowedHosts: Set<String> = emptySet()
    ) = SubscriptionFetchRequest(
        subscriptionId = "test",
        url = "https://$testHost:${server.port}$path",
        origin = origin,
        allowedHosts = allowedHosts,
        etag = etag
    )

    private fun reply(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/plain")
        .body(Buffer().write(body.toByteArray()))
        .build()

    private fun redirect(server: MockWebServer, path: String): MockResponse = MockResponse.Builder()
        .code(302)
        .addHeader("Location", "https://$testHost:${server.port}$path")
        .build()

    private fun failureReason(result: SubscriptionFetchResult): SubscriptionFailure =
        (result as SubscriptionFetchResult.Failure).reason

    @Test
    fun downloadsTheListAndKeepsItsEtag() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("ETag", "\"v1\"")
                .body(Buffer().write("||ads.example^\n".toByteArray()))
                .build()
        )

        val result = fetcher().fetch(request(server))

        assertTrue(result is SubscriptionFetchResult.Success)
        val success = result as SubscriptionFetchResult.Success
        assertEquals("||ads.example^\n", success.text)
        assertEquals("\"v1\"", success.etag)
        assertEquals(server.requestCount, 1)
    }

    @Test
    fun sendsAConditionalRequestWhenAnEtagIsStored() {
        val server = startServer()
        server.enqueue(reply("||ads.example^\n"))

        fetcher().fetch(request(server, etag = "\"v1\""))

        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun anUnchangedListIsNotTransferredAgain() {
        val server = startServer()
        server.enqueue(MockResponse.Builder().code(304).build())

        assertEquals(
            SubscriptionFetchResult.NotModified,
            fetcher().fetch(request(server, etag = "\"v1\""))
        )
    }

    @Test
    fun anUnusableStoredEtagIsSimplyNotSent() {
        val server = startServer()
        server.enqueue(reply("||ads.example^\n"))

        fetcher().fetch(request(server, etag = "broken\nvalue"))

        assertNull(server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun threeRedirectsAreFollowed() {
        val server = startServer()
        repeat(3) { hop -> server.enqueue(redirect(server, "/hop$hop")) }
        server.enqueue(reply("||ads.example^\n"))

        val result = fetcher().fetch(request(server))

        assertTrue(result is SubscriptionFetchResult.Success)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun theFourthRedirectIsRefused() {
        val server = startServer()
        repeat(4) { hop -> server.enqueue(redirect(server, "/hop$hop")) }
        server.enqueue(reply("||never.example^\n"))

        val result = fetcher().fetch(request(server))

        assertEquals(SubscriptionFailure.REDIRECT_LIMIT, failureReason(result))
        assertEquals("the fourth hop must not be requested", 4, server.requestCount)
    }

    @Test
    fun aRedirectToAPrivateAddressIsRefusedBeforeItIsRequested() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://127.0.0.1:${server.port}/private.txt")
                .build()
        )
        server.enqueue(reply("||should.never.be.reached^\n"))

        val result = fetcher().fetch(request(server))

        assertEquals(SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS, failureReason(result))
        assertEquals("only the first hop may reach the server", 1, server.requestCount)
    }

    @Test
    fun aRedirectToALocalNameIsRefused() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "https://localhost/x").build()
        )

        val result = fetcher().fetch(request(server))

        assertEquals(SubscriptionFailure.BLOCKED_LOCAL_HOST, failureReason(result))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRedirectToAnInsecureSchemeIsRefused() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "http://$testHost/x").build()
        )

        assertEquals(SubscriptionFailure.INSECURE_URL, failureReason(fetcher().fetch(request(server))))
    }

    @Test
    fun aRedirectWithoutALocationIsRefused() {
        val server = startServer()
        server.enqueue(MockResponse.Builder().code(302).build())

        assertEquals(SubscriptionFailure.BAD_REDIRECT, failureReason(fetcher().fetch(request(server))))
    }

    /** Even handed a client that would follow redirects itself, every hop is still validated. */
    @Test
    fun aRedirectFollowingClientIsForcedThroughThePerHopChecks() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://127.0.0.1:${server.port}/private.txt")
                .build()
        )
        server.enqueue(reply("||should.never.be.reached^\n"))

        val result = fetcher(followRedirects = true).fetch(request(server))

        assertEquals(SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS, failureReason(result))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anOversizedListStopsAtTheCap() {
        val server = startServer()
        val body = "||ads.example^\n".repeat(20_000)
        server.enqueue(reply(body))

        val result = fetcher(limits = FetchLimits(maxBytes = 64 * 1024)).fetch(request(server))

        assertEquals(SubscriptionFailure.TOO_LARGE, failureReason(result))
        assertTrue("the body really is larger than the cap", body.length > 64 * 1024)
    }

    @Test
    fun aSlowServerIsReportedAsATimeout() {
        val server = startServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .bodyDelay(3, TimeUnit.SECONDS)
                .body(Buffer().write("||ads.example^\n".toByteArray()))
                .build()
        )

        val result = fetcher(
            limits = FetchLimits(readTimeoutMillis = 250, wholeRequestTimeoutMillis = 2_000)
        ).fetch(request(server))

        assertEquals(SubscriptionFailure.TIMEOUT, failureReason(result))
    }

    @Test
    fun exceedingTheWholeRequestDeadlineStopsTheRedirectChain() {
        val server = startServer()
        server.enqueue(redirect(server, "/hop1"))
        server.enqueue(reply("||never.example^\n"))

        // The first two reads of the clock are the start and the first hop's check; from then on the
        // deadline has passed, so the second hop must not be requested at all.
        var calls = 0
        val clock = { if (calls++ < 2) 0L else 60_000L }

        val result = fetcher(
            limits = FetchLimits(wholeRequestTimeoutMillis = 20_000),
            clock = clock
        ).fetch(request(server))

        assertEquals(SubscriptionFailure.DEADLINE_EXCEEDED, failureReason(result))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anErrorStatusIsReportedAsAFailure() {
        val server = startServer()
        server.enqueue(MockResponse.Builder().code(503).build())

        assertEquals(SubscriptionFailure.HTTP_STATUS, failureReason(fetcher().fetch(request(server))))
    }

    @Test
    fun anEmptyResponseIsReportedAsAFailure() {
        val server = startServer()
        server.enqueue(reply("   \n"))

        assertEquals(SubscriptionFailure.EMPTY_BODY, failureReason(fetcher().fetch(request(server))))
    }

    @Test
    fun anUntrustedCertificateIsNeverAccepted() {
        // A server the default trust store refuses. Nothing in this feature may weaken that.
        val certificate = HeldCertificate.Builder()
            .commonName("other.test")
            .addSubjectAlternativeName(testHost)
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
        servers.add(server)
        server.enqueue(reply("||ads.example^\n"))

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .build()
        clients.add(client)

        val result = SubscriptionFetcher(guard = guard(), client = client).fetch(request(server))

        assertEquals(SubscriptionFailure.TLS_FAILED, failureReason(result))
    }

    @Test
    fun aBuiltInSourceCannotBePointedAnywhereElse() {
        val server = startServer()
        server.enqueue(reply("||ads.example^\n"))

        val result = SubscriptionFetcher(guard = guard(), client = newClient()).fetch(
            request(
                server,
                origin = SubscriptionOrigin.BUILT_IN,
                allowedHosts = setOf("cdn.jsdelivr.net", "raw.githubusercontent.com")
            )
        )

        assertEquals(SubscriptionFailure.HOST_NOT_ALLOWED, failureReason(result))
        assertEquals("a host the tier does not publish is never contacted", 0, server.requestCount)
    }

    @Test
    fun anInsecureUrlIsRefusedWithoutAnyRequest() {
        val server = startServer()

        val result = SubscriptionFetcher(guard = guard(), client = newClient()).fetch(
            request(server).copy(url = "http://$testHost:${server.port}/list.txt")
        )

        assertEquals(SubscriptionFailure.INSECURE_URL, failureReason(result))
        assertEquals(0, server.requestCount)
    }

    /**
     * The pre-flight check refuses a privately resolving host. The same policy inside the client's DNS
     * hook is what closes the rebinding gap between the two lookups; that half is covered directly by
     * `SubscriptionHostGuardTest.resolveForConnection`.
     */
    @Test
    fun aHostThatResolvesPrivatelyIsRefusedBeforeConnecting() {
        val server = startServer()
        val rebinding = SubscriptionHostGuard { listOf(InetAddress.getByName("10.0.0.5")) }

        val result = SubscriptionFetcher(guard = rebinding, client = newClient()).fetch(request(server))

        assertEquals(SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS, failureReason(result))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnresolvableHostIsAConnectionFailure() {
        val server = startServer()

        val result = SubscriptionFetcher(guard = guard(), client = newClient())
            .fetch(request(server).copy(url = "https://nowhere.example.com/list.txt"))

        assertEquals(SubscriptionFailure.CONNECT_FAILED, failureReason(result))
    }

    @Test(timeout = 10_000)
    fun theStreamingReaderStopsAtTheCapInsteadOfBufferingEverything() {
        // A source that never ends: an implementation that buffered first would never return.
        val endless = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                sink.write(ByteArray(4 * 1024))
                return 4 * 1024L
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }

        assertEquals(BodyRead.TooLarge, SubscriptionBodyReader.read(endless, maxBytes = 1024))
    }

    @Test
    fun theReaderReturnsTheWholeBodyUnderTheCap() {
        val body = "||ads.example^\n||more.example^\n"

        val outcome = SubscriptionBodyReader.read(Buffer().write(body.toByteArray()), maxBytes = 1024)

        assertTrue(outcome is BodyRead.Ok)
        assertEquals(body, (outcome as BodyRead.Ok).text)
        assertEquals(body.toByteArray().size.toLong(), outcome.byteCount)
    }

    @Test
    fun aZeroCapAcceptsNothing() {
        assertFalse(SubscriptionBodyReader.read(Buffer(), maxBytes = 0) is BodyRead.Ok)
    }

    @Test
    fun theSelfBuiltFetcherClosesCleanlyAndOnlyClosesItsOwnClient() {
        // create() owns its client; closing it twice must be harmless, and an injected client must be
        // left alone by a fetcher that does not own it.
        val created = SubscriptionFetcher.create(guard = guard())
        created.close()
        created.close()

        val server = startServer()
        val injected = newClient()
        val fetcher = SubscriptionFetcher(guard = guard(), client = injected)

        fetcher.close()

        server.enqueue(reply("||ads.example^\n"))
        assertTrue(fetcher.fetch(request(server)) is SubscriptionFetchResult.Success)
    }
}
