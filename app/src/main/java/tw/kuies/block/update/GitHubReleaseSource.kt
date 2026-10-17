package tw.kuies.block.update

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** How long one release lookup may take, so a slow or silent server cannot hold the check open. */
data class UpdateTimeouts(
    /** The whole request, connect and read included. */
    val wholeRequestMillis: Long = 15_000,
    val connectMillis: Long = 5_000,
    val readMillis: Long = 10_000
) {
    companion object {
        val DEFAULT = UpdateTimeouts()
    }
}

/**
 * The latest stable release, straight from GitHub's public API.
 *
 * The request is anonymous on purpose: no token, no key and no credential is ever embedded in the app
 * or sent from it, so the worst an intercepted or hostile response can do is feed the dialog a wrong
 * version number — it can never leak a secret or act on the caller's behalf. The endpoint is a fixed
 * `https` address and redirects are not followed, so a response can only ever come from
 * `api.github.com` itself.
 *
 * Every non-success answer is mapped to one [UpdateCheckFailure] and nothing else from it is kept: no
 * status code, header or server message is shown or logged, and the body of a failure is never parsed.
 * A success body is bounded and handed to [ReleaseJson], which returns data only.
 */
class GitHubReleaseSource(
    client: OkHttpClient,
    private val endpoint: HttpUrl = LATEST_STABLE_RELEASE,
    private val timeouts: UpdateTimeouts = UpdateTimeouts.DEFAULT,
    private val ownsClient: Boolean = false
) : ReleaseSource {

    /** The bounds below are applied to the client this source actually uses, injected or not. */
    private val client: OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeouts.wholeRequestMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
        .build()

    override fun latestStableRelease(): ReleaseLookup {
        val request = Request.Builder()
            .url(endpoint)
            .header(ACCEPT, ACCEPT_VALUE)
            .header(API_VERSION, API_VERSION_VALUE)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response -> classify(response) }
        } catch (_: InterruptedIOException) {
            ReleaseLookup.Failed(UpdateCheckFailure.TIMEOUT)
        } catch (_: SSLException) {
            ReleaseLookup.Failed(UpdateCheckFailure.NETWORK)
        } catch (_: IOException) {
            ReleaseLookup.Failed(UpdateCheckFailure.NETWORK)
        }
    }

    private fun classify(response: Response): ReleaseLookup {
        when (response.code) {
            HTTP_OK -> Unit
            // GitHub answers a spent anonymous budget with 403 and a 429 when it says so explicitly;
            // both mean the same thing to a user, so they read the same.
            HTTP_FORBIDDEN, HTTP_TOO_MANY_REQUESTS ->
                return ReleaseLookup.Failed(UpdateCheckFailure.RATE_LIMITED)
            HTTP_NOT_FOUND -> return ReleaseLookup.Failed(UpdateCheckFailure.NOT_FOUND)
            else -> return ReleaseLookup.Failed(UpdateCheckFailure.SERVER)
        }

        val body = readBounded(response) ?: return ReleaseLookup.Failed(UpdateCheckFailure.UNREADABLE)
        val release = ReleaseJson.parse(body) ?: return ReleaseLookup.Failed(UpdateCheckFailure.UNREADABLE)
        return ReleaseLookup.Found(release)
    }

    /** The whole body up to [MAX_BODY_BYTES]; a larger answer is refused rather than buffered. */
    private fun readBounded(response: Response): String? {
        val source = response.body.source()
        if (source.request(MAX_BODY_BYTES + 1L)) return null
        return source.readUtf8()
    }

    /** Releases the connection pool. Only does anything when this instance built its own client. */
    fun close() {
        if (!ownsClient) return
        val dispatcher = client.dispatcher
        runCatching {
            // Evicting pooled TLS sockets writes close_notify alerts over the network, so it must
            // never happen on the main thread; the dispatcher's own workers are the safe place.
            dispatcher.executorService.execute { runCatching { client.connectionPool.evictAll() } }
        }
        dispatcher.executorService.shutdown()
    }

    companion object {
        /** The public, unauthenticated endpoint for the newest release that is not a draft or a pre-release. */
        const val ENDPOINT = "https://api.github.com/repos/kuies001/KuiesBlock/releases/latest"

        private val LATEST_STABLE_RELEASE: HttpUrl = ENDPOINT.toHttpUrl()

        private const val ACCEPT = "Accept"
        private const val ACCEPT_VALUE = "application/vnd.github+json"
        private const val API_VERSION = "X-GitHub-Api-Version"
        private const val API_VERSION_VALUE = "2022-11-28"
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MAX_BODY_BYTES = 256L * 1024

        /** A source that owns its OkHttp client, ready for a long-lived caller to close. */
        fun create(): GitHubReleaseSource = GitHubReleaseSource(
            client = OkHttpClient.Builder().build(),
            endpoint = LATEST_STABLE_RELEASE,
            timeouts = UpdateTimeouts.DEFAULT,
            ownsClient = true
        )
    }
}
