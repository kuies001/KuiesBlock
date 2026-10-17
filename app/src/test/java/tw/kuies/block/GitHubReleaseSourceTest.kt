package tw.kuies.block

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tw.kuies.block.update.AppVersion
import tw.kuies.block.update.GitHubReleaseSource
import tw.kuies.block.update.ReleaseInfo
import tw.kuies.block.update.ReleaseLookup
import tw.kuies.block.update.UpdateCheckFailure
import tw.kuies.block.update.UpdateTimeouts

/**
 * The HTTP path against a real server, so every status code and every failure maps to the reason the
 * screen will show.
 *
 * The endpoint is injected, so nothing here needs GitHub: the harness server answers instead. What the
 * production endpoint is, and that it is an anonymous `https` address, is asserted separately below.
 */
class GitHubReleaseSourceTest {

    private val server = MockWebServer()
    private val clients = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.connectionPool.evictAll() } }
        runCatching { server.close() }
    }

    private fun source(timeouts: UpdateTimeouts = UpdateTimeouts.DEFAULT): GitHubReleaseSource {
        val client = OkHttpClient.Builder().build()
        clients += client
        return GitHubReleaseSource(
            client = client,
            endpoint = server.url("/repos/kuies001/KuiesBlock/releases/latest"),
            timeouts = timeouts
        )
    }

    private fun reply(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(Buffer().write(body.toByteArray()))
        .build()

    private fun stableReleaseJson(tag: String = "v0.4.1") = """
        {
          "tag_name": "$tag",
          "draft": false,
          "prerelease": false,
          "body": "## 修正\n- 一些問題",
          "html_url": "https://github.com/kuies001/KuiesBlock/releases/tag/$tag"
        }
    """.trimIndent()

    private fun failureReason(result: ReleaseLookup): UpdateCheckFailure =
        (result as ReleaseLookup.Failed).reason

    private fun found(result: ReleaseLookup): ReleaseInfo =
        (result as ReleaseLookup.Found).release

    @Test
    fun aStableReleaseIsReadFromARealResponse() {
        server.enqueue(reply(200, stableReleaseJson()))

        val release = found(source().latestStableRelease())

        assertEquals("0.4.1", release.version.text())
        assertEquals("v0.4.1", release.tagName)
        assertEquals("## 修正\n- 一些問題", release.notes)
        assertEquals("https://github.com/kuies001/KuiesBlock/releases/tag/v0.4.1", release.pageUrl)
    }

    @Test
    fun theRequestIsAnAnonymousGetToTheReleaseEndpoint() {
        server.enqueue(reply(200, stableReleaseJson()))

        source().latestStableRelease()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/repos/kuies001/KuiesBlock/releases/latest", recorded.url.encodedPath)
        assertEquals("application/vnd.github+json", recorded.headers["Accept"])
        // No credential is ever sent: the API is used anonymously.
        assertNull(recorded.headers["Authorization"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aNotFoundAnswerReadsAsNoReleaseFound() {
        server.enqueue(reply(404, "{}"))

        assertEquals(UpdateCheckFailure.NOT_FOUND, failureReason(source().latestStableRelease()))
    }

    @Test
    fun aSpentRateLimitReadsAsRateLimited() {
        server.enqueue(reply(403, """{"message":"API rate limit exceeded"}"""))

        assertEquals(UpdateCheckFailure.RATE_LIMITED, failureReason(source().latestStableRelease()))
    }

    @Test
    fun aTooManyRequestsAnswerReadsAsRateLimitedToo() {
        server.enqueue(reply(429, "{}"))

        assertEquals(UpdateCheckFailure.RATE_LIMITED, failureReason(source().latestStableRelease()))
    }

    @Test
    fun anyOtherServerErrorReadsAsAServerFailure() {
        listOf(400, 401, 500, 503).forEach { code ->
            server.enqueue(reply(code, "{}"))
            assertEquals(
                "status $code",
                UpdateCheckFailure.SERVER,
                failureReason(source().latestStableRelease())
            )
        }
    }

    @Test
    fun aSuccessWithAnUnreadableBodyIsRefused() {
        server.enqueue(reply(200, "this is not json"))

        assertEquals(UpdateCheckFailure.UNREADABLE, failureReason(source().latestStableRelease()))
    }

    @Test
    fun aSuccessWithAPreReleaseOrDraftFlagIsRefused() {
        server.enqueue(reply(200, """{"tag_name":"v0.5.0","prerelease":true}"""))
        server.enqueue(reply(200, """{"tag_name":"v0.5.0","draft":true}"""))

        assertEquals(UpdateCheckFailure.UNREADABLE, failureReason(source().latestStableRelease()))
        assertEquals(UpdateCheckFailure.UNREADABLE, failureReason(source().latestStableRelease()))
    }

    @Test
    fun anEmptyBodyIsRefused() {
        server.enqueue(reply(200, ""))

        assertEquals(UpdateCheckFailure.UNREADABLE, failureReason(source().latestStableRelease()))
    }

    @Test
    fun aSilentServerTimesOutRatherThanHanging() {
        // A socket that accepts the connection and then says nothing: the read timeout must end it.
        val silent = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val client = OkHttpClient.Builder().build()
            clients += client
            val source = GitHubReleaseSource(
                client = client,
                endpoint = "http://127.0.0.1:${silent.localPort}/releases/latest".toHttpUrl(),
                timeouts = UpdateTimeouts(
                    wholeRequestMillis = 500,
                    connectMillis = 500,
                    readMillis = 500
                )
            )

            assertEquals(UpdateCheckFailure.TIMEOUT, failureReason(source.latestStableRelease()))
        } finally {
            runCatching { silent.close() }
        }
    }

    @Test
    fun anUnreachableServerReadsAsANetworkFailure() {
        // Nothing is listening on this port: the connection cannot be made at all.
        val closed = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = closed.localPort
        closed.close()
        val client = OkHttpClient.Builder().build()
        clients += client
        val source = GitHubReleaseSource(
            client = client,
            endpoint = "http://127.0.0.1:$port/releases/latest".toHttpUrl(),
            timeouts = UpdateTimeouts(1_000, 1_000, 1_000)
        )

        assertEquals(UpdateCheckFailure.NETWORK, failureReason(source.latestStableRelease()))
    }

    @Test
    fun theProductionEndpointIsAnAnonymousHttpsApiAddress() {
        val endpoint = GitHubReleaseSource.ENDPOINT

        assertTrue("the endpoint must be https", endpoint.startsWith("https://"))
        assertEquals("api.github.com", endpoint.toHttpUrl().host)
        // A token would have to be embedded somewhere; there is none, not even a query parameter.
        assertNull(endpoint.toHttpUrl().query)
    }
}
