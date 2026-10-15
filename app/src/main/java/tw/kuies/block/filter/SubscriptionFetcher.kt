package tw.kuies.block.filter

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.Source

/**
 * Why a subscription could not be fetched or turned into rules.
 *
 * An enum, not an exception message: the UI shows [label] and the rest of the app matches on the
 * value. No reason carries a URL, a domain or any list content.
 */
enum class SubscriptionFailure(val label: String) {
    INVALID_URL("invalid URL"),
    INSECURE_URL("must be an https address"),
    BLOCKED_LOCAL_HOST("local address not allowed"),
    BLOCKED_PRIVATE_ADDRESS("private address not allowed"),
    HOST_NOT_ALLOWED("host not allowed for this list"),
    BAD_REDIRECT("invalid redirect"),
    REDIRECT_LIMIT("too many redirects"),
    TOO_LARGE("list is too large"),
    HTTP_STATUS("unexpected server response"),
    EMPTY_BODY("empty response"),
    TIMEOUT("timed out"),
    DEADLINE_EXCEEDED("took too long"),
    TLS_FAILED("secure connection failed"),
    CONNECT_FAILED("could not connect"),
    IO("network error"),
    TRUNCATED_LIST("list is longer than the parser accepts"),
    PARSE_TIMEOUT("taking too long to read the list"),
    EMPTY_LIST("list contained no usable rules"),
    STORE_WRITE_FAILED("could not store the list")
}

/** One download attempt: where from, on whose behalf, and what the stored copy already looks like. */
data class SubscriptionFetchRequest(
    val subscriptionId: String,
    val url: String,
    val origin: SubscriptionOrigin,
    /** Hosts a built-in source may contact; ignored for a user source. */
    val allowedHosts: Set<String> = emptySet(),
    /** ETag of the stored copy, so an unchanged list costs no bandwidth. */
    val etag: String? = null
)

/** The outcome of one download attempt. */
sealed interface SubscriptionFetchResult {

    /** The list text as downloaded. Never executed, interpreted as a command or followed as a URL. */
    data class Success(
        val text: String,
        val byteCount: Long,
        val etag: String?
    ) : SubscriptionFetchResult

    /** The server confirmed the stored copy is still current; nothing was transferred. */
    data object NotModified : SubscriptionFetchResult

    data class Failure(val reason: SubscriptionFailure) : SubscriptionFetchResult
}

/** The fetch seam, so the update pipeline can be exercised without a network. */
fun interface SubscriptionSource {
    fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResult
}

/** Bounds for one download. */
data class FetchLimits(
    /** Hard cap on the response body; the transfer is aborted as soon as it is exceeded. */
    val maxBytes: Long = 16L * 1024 * 1024,
    /** Most redirects that will be followed; each hop is validated again. */
    val maxRedirects: Int = 3,
    /** Whole-request deadline, including every redirect hop. */
    val wholeRequestTimeoutMillis: Long = 20_000,
    val connectTimeoutMillis: Long = 5_000,
    val readTimeoutMillis: Long = 15_000
) {
    companion object {
        val DEFAULT = FetchLimits()
    }
}

/** What [SubscriptionBodyReader] made of a response body. */
sealed interface BodyRead {
    data class Ok(val text: String, val byteCount: Long) : BodyRead
    data object TooLarge : BodyRead
}

/**
 * Reads a response body up to [maxBytes], aborting as soon as the limit is exceeded.
 *
 * The body arrives in chunks and is never buffered beyond the cap, so a server that streams gigabytes
 * cannot exhaust memory before the limit is noticed.
 */
object SubscriptionBodyReader {

    private const val CHUNK_BYTES = 8 * 1024

    fun read(source: Source, maxBytes: Long): BodyRead {
        if (maxBytes <= 0L) return BodyRead.TooLarge

        val accumulated = Buffer()
        var total = 0L
        while (true) {
            // okio's contract is a positive count or -1; anything else ends the read here.
            val read = source.read(accumulated, CHUNK_BYTES.toLong())
            if (read <= 0L) break
            total += read
            if (total > maxBytes) return BodyRead.TooLarge
        }
        return BodyRead.Ok(accumulated.readUtf8(), total)
    }
}

/**
 * Downloads one subscription over HTTPS.
 *
 * Rules of engagement, all enforced here:
 *  * **https only**, with OkHttp's own certificate and hostname validation. TLS is never weakened.
 *  * **SSRF defence**: [SubscriptionHostGuard] runs before every connection and again on every
 *    redirect hop, so a private, loopback, link-local or local host can never be reached, and a
 *    name that resolves privately is refused.
 *  * **Built-in sources** may only use the hosts their tier publishes; a user source may use any
 *    public https host.
 *  * **At most [FetchLimits.maxRedirects] redirects**, each re-validated, within a whole-request
 *    deadline, with conditional requests (`If-None-Match`) so an unchanged list costs no bandwidth.
 *  * **A 16 MB streaming cap** by default: the transfer stops as soon as the limit is passed.
 *
 * The downloaded text is data. It is never executed, never interpreted as a command, and a URL inside
 * it is never followed.
 *
 * This class never runs on the packet path and never blocks the VPN service: give it to a background
 * dispatcher ([SubscriptionUpdater] does).
 *
 * The bounds in [limits] — including the connect, read and whole-request timeouts — are applied to the
 * client this fetcher actually uses, injected or not, so what is enforced is always the fetcher's
 * contract rather than whatever the client was built with.
 */
class SubscriptionFetcher(
    private val guard: SubscriptionHostGuard,
    client: OkHttpClient,
    private val limits: FetchLimits = FetchLimits.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ownsClient: Boolean = false
) : SubscriptionSource {

    /**
     * A client that does not follow redirects and obeys this fetcher's timeouts, derived from [client]
     * so an injected client's dispatcher, connection pool, DNS and TLS settings are still used: a
     * client that followed redirects by itself would skip the per-hop validation below.
     */
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(limits.wholeRequestTimeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(limits.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(limits.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResult {
        var current = request.url.toHttpUrlOrNull() ?: return fail(SubscriptionFailure.INVALID_URL)
        val startedAt = clock()
        var redirects = 0

        while (true) {
            guard.check(current, request.origin, request.allowedHosts)?.let { return fail(it) }
            if (clock() - startedAt > limits.wholeRequestTimeoutMillis) {
                return fail(SubscriptionFailure.DEADLINE_EXCEEDED)
            }

            when (val hop = execute(current, sanitiseEtag(request.etag))) {
                is Hop.Body -> {
                    if (hop.text.isBlank()) return fail(SubscriptionFailure.EMPTY_BODY)
                    return SubscriptionFetchResult.Success(hop.text, hop.byteCount, hop.etag)
                }

                Hop.NotModified -> return SubscriptionFetchResult.NotModified

                is Hop.Redirect -> {
                    val location = hop.location
                    if (location.isNullOrBlank()) return fail(SubscriptionFailure.BAD_REDIRECT)
                    if (redirects >= limits.maxRedirects) return fail(SubscriptionFailure.REDIRECT_LIMIT)
                    redirects++
                    current = current.resolve(location) ?: return fail(SubscriptionFailure.BAD_REDIRECT)
                }

                is Hop.Failed -> return fail(hop.reason)
            }
        }
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

    private fun execute(url: HttpUrl, etag: String?): Hop {
        val builder = Request.Builder().url(url).get()
        if (etag != null) builder.header(IF_NONE_MATCH, etag)

        return try {
            client.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    HTTP_OK -> readBody(response)
                    HTTP_NOT_MODIFIED -> Hop.NotModified
                    in HTTP_REDIRECT -> Hop.Redirect(response.header(LOCATION))
                    else -> Hop.Failed(SubscriptionFailure.HTTP_STATUS)
                }
            }
        } catch (_: InterruptedIOException) {
            Hop.Failed(SubscriptionFailure.TIMEOUT)
        } catch (_: SSLException) {
            Hop.Failed(SubscriptionFailure.TLS_FAILED)
        } catch (io: IOException) {
            Hop.Failed(blockedReason(io) ?: classify(io))
        }
    }

    private fun readBody(response: Response): Hop =
        when (val read = SubscriptionBodyReader.read(response.body.source(), limits.maxBytes)) {
            is BodyRead.Ok -> Hop.Body(read.text, read.byteCount, sanitiseEtag(response.header(ETAG)))
            BodyRead.TooLarge -> Hop.Failed(SubscriptionFailure.TOO_LARGE)
        }

    private fun classify(io: IOException): SubscriptionFailure = when (io) {
        is UnknownHostException, is ConnectException -> SubscriptionFailure.CONNECT_FAILED
        else -> SubscriptionFailure.IO
    }

    /** The refusal the client's DNS hook raised, if any, however OkHttp wrapped it. */
    private fun blockedReason(throwable: Throwable?): SubscriptionFailure? {
        var current = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is BlockedHostException) return current.reason
            current = current.cause
            depth++
        }
        return null
    }

    /** A header value has to be usable as a request header; anything else is simply not sent. */
    private fun sanitiseEtag(etag: String?): String? {
        val trimmed = etag?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_ETAG_LENGTH) return null
        return if (trimmed.any { it < ' ' || it == '\u007F' }) null else trimmed
    }

    private fun fail(reason: SubscriptionFailure): SubscriptionFetchResult =
        SubscriptionFetchResult.Failure(reason)

    private sealed interface Hop {
        data class Body(val text: String, val byteCount: Long, val etag: String?) : Hop
        data object NotModified : Hop
        data class Redirect(val location: String?) : Hop
        data class Failed(val reason: SubscriptionFailure) : Hop
    }

    companion object {
        const val IF_NONE_MATCH = "If-None-Match"
        private const val ETAG = "ETag"
        private const val LOCATION = "Location"
        private const val HTTP_OK = 200
        private const val HTTP_NOT_MODIFIED = 304
        private val HTTP_REDIRECT = 300..399
        private const val MAX_ETAG_LENGTH = 256
        private const val MAX_CAUSE_DEPTH = 8

        /**
         * A fetcher that owns its OkHttp client, already pinned to [SubscriptionHostGuard] for DNS so
         * even a rebinding name cannot reach a private address.
         */
        fun create(
            guard: SubscriptionHostGuard = SubscriptionHostGuard(),
            limits: FetchLimits = FetchLimits.DEFAULT,
            clock: () -> Long = System::currentTimeMillis
        ): SubscriptionFetcher = SubscriptionFetcher(
            guard = guard,
            client = buildClient(guard, limits),
            limits = limits,
            clock = clock,
            ownsClient = true
        )

        private fun buildClient(guard: SubscriptionHostGuard, limits: FetchLimits): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .dns { hostname -> guard.resolveForConnection(hostname) }
                .callTimeout(limits.wholeRequestTimeoutMillis, TimeUnit.MILLISECONDS)
                .connectTimeout(limits.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(limits.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
    }
}
