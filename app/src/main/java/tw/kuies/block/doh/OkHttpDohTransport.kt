package tw.kuies.block.doh

import android.os.Looper
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsResponseOutcome

/**
 * DoH client built on OkHttp, so TLS and certificate validation are never hand-rolled.
 *
 * Every socket OkHttp creates is passed to [VpnSocketProtector] before it connects, so the
 * upstream connection leaves through the underlying network instead of looping back into the
 * tunnel. Hostname resolution goes through [HostnameResolver] (a plain-DNS bootstrap) rather
 * than the system resolver, which would recurse into our own VPN DNS.
 *
 * TLS hostname and certificate validation stay at OkHttp's defaults and are never disabled.
 */
class OkHttpDohTransport(
    private val endpoint: DohEndpoint,
    protector: VpnSocketProtector,
    resolver: HostnameResolver,
    callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MS,
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MS
) : DohTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Dns { hostname -> resolver.resolve(hostname) })
        .socketFactory(protectingSocketFactory(protector))
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .retryOnConnectionFailure(false)
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override fun exchange(queryBytes: ByteArray): DohResult {
        val queryId = readTransactionId(queryBytes)
            ?: return DohResult.Failure(DohFailure.MALFORMED_RESPONSE)

        val request = try {
            Request.Builder()
                .url(endpoint.url)
                .header("Accept", DNS_MESSAGE)
                .post(queryBytes.toRequestBody(DNS_MESSAGE_TYPE))
                .build()
        } catch (invalid: IllegalArgumentException) {
            return DohResult.Failure(DohFailure.INVALID_ENDPOINT, detail = invalid.javaClass.simpleName)
        }

        return try {
            client.newCall(request).execute().use { response -> validate(response, queryId) }
        } catch (timeout: SocketTimeoutException) {
            DohResult.Failure(DohFailure.TIMEOUT, detail = timeout.javaClass.simpleName)
        } catch (tls: SSLException) {
            DohResult.Failure(DohFailure.TLS_FAILED, detail = tls.javaClass.simpleName)
        } catch (certificate: CertificateException) {
            DohResult.Failure(DohFailure.TLS_FAILED, detail = certificate.javaClass.simpleName)
        } catch (unresolved: UnknownHostException) {
            DohResult.Failure(DohFailure.CONNECT_FAILED, detail = unresolved.javaClass.simpleName)
        } catch (refused: ConnectException) {
            DohResult.Failure(DohFailure.CONNECT_FAILED, detail = refused.javaClass.simpleName)
        } catch (io: IOException) {
            DohResult.Failure(DohFailure.IO, detail = io.javaClass.simpleName)
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        val pool = client.connectionPool
        // Evicting pooled TLS sockets writes close_notify alerts over the network, which
        // causes NetworkOnMainThreadException if executed on Android's main looper.
        // Always evict sockets on a background thread.
        val evictTask = Runnable {
            runCatching { pool.evictAll() }
        }
        val isMainLooper = try {
            Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()
        } catch (_: Throwable) {
            false
        }
        if (isMainLooper) {
            Thread(evictTask, "kuies-doh-evict").start()
        } else {
            evictTask.run()
        }
    }

    private fun validate(response: Response, queryId: Int): DohResult {
        if (response.code != HTTP_OK) {
            return DohResult.Failure(DohFailure.HTTP_STATUS, httpStatus = response.code)
        }
        val contentType = response.body.contentType()?.toString()
        if (contentType == null || !contentType.lowercase().startsWith(DNS_MESSAGE)) {
            return DohResult.Failure(DohFailure.CONTENT_TYPE, detail = contentType)
        }

        // Reading the body can itself raise SocketTimeoutException, so it is deliberately left to
        // the classification in exchange() rather than being flattened into a generic IO failure.
        val source = response.body.source()
        // Bound the read so a misbehaving upstream cannot balloon our memory.
        if (source.request(MAX_RESPONSE_BYTES + 1L)) {
            return DohResult.Failure(DohFailure.TOO_LARGE)
        }
        val payload = source.readByteArray()

        if (payload.isEmpty()) return DohResult.Failure(DohFailure.EMPTY_BODY)

        return when (val parsed = DnsMessageReader.readResponse(payload, payload.size)) {
            is DnsResponseOutcome.Invalid -> DohResult.Failure(DohFailure.MALFORMED_RESPONSE)
            is DnsResponseOutcome.Ok -> {
                if (parsed.response.header.id != queryId) {
                    DohResult.Failure(DohFailure.TRANSACTION_ID_MISMATCH)
                } else {
                    DohResult.Success(payload)
                }
            }
        }
    }

    private fun protectingSocketFactory(protector: VpnSocketProtector): SocketFactory {
        val delegate = SocketFactory.getDefault()
        return object : SocketFactory() {
            override fun createSocket(): Socket = protectSocket(delegate.createSocket())

            override fun createSocket(host: String, port: Int): Socket =
                protectSocket(delegate.createSocket(host, port))

            override fun createSocket(
                host: String,
                port: Int,
                localHost: InetAddress,
                localPort: Int
            ): Socket = protectSocket(delegate.createSocket(host, port, localHost, localPort))

            override fun createSocket(host: InetAddress, port: Int): Socket =
                protectSocket(delegate.createSocket(host, port))

            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int
            ): Socket = protectSocket(delegate.createSocket(address, port, localAddress, localPort))

            private fun protectSocket(socket: Socket): Socket {
                protector.protect(socket)
                return socket
            }
        }
    }

    private fun readTransactionId(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    companion object {
        const val DNS_MESSAGE = "application/dns-message"
        private const val HTTP_OK = 200
        private const val MAX_RESPONSE_BYTES = 65_535L
        private const val MAX_IDLE_CONNECTIONS = 2
        private const val KEEP_ALIVE_MINUTES = 5L
        private const val DEFAULT_CALL_TIMEOUT_MS = 8_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_READ_TIMEOUT_MS = 5_000L

        private val DNS_MESSAGE_TYPE = DNS_MESSAGE.toMediaType()
    }
}
