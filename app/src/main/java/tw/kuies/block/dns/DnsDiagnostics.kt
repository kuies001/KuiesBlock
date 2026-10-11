package tw.kuies.block.dns

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * Read-only DNS connectivity / TLS diagnostic for the configured endpoint.
 *
 * Two deliberate design points:
 *
 *  * **It resolves the DoH hostname through [resolveHostname], not through the system resolver.**
 *    Android's Private DNS ("specify a hostname") takes over the *system* resolver, so resolving
 *    through it measures Android's encrypted channel rather than KuiesBlock's own path — and with
 *    the VPN up it would even route the lookup into our own tunnel. The caller passes the same
 *    protected plain-DNS bootstrap the DoH client uses, so the diagnostic measures the real path.
 *  * **It connects by address but still verifies the hostname.** SNI and
 *    `endpointIdentificationAlgorithm = "HTTPS"` are set, so the certificate chain and the host name
 *    are validated exactly as OkHttp would. TLS verification is never disabled.
 *
 * The resolved addresses live only in this method's local scope: they are never stored in
 * [DnsDiagnosticReport] and never logged, so the user's own DNS server address cannot reach the UI,
 * a screenshot, or Logcat.
 *
 * Must be called from a background thread; it performs blocking network I/O.
 */
class DnsDiagnostics(
    private val config: DnsConfig,
    private val resolveHostname: (String) -> List<InetAddress>
) {

    fun run(): DnsDiagnosticReport {
        val host = config.host
        val addresses = runCatching { resolveHostname(host) }.getOrElse {
            Log.w(TAG, "DoH hostname resolution failed: ${it.javaClass.simpleName}")
            emptyList()
        }
        val hostResolved = addresses.isNotEmpty()
        val probes = if (hostResolved) {
            candidatePorts().map { candidate -> probeCandidate(host, addresses, candidate) }
        } else {
            emptyList()
        }

        return DnsDiagnosticReport(
            host = host,
            protocol = config.protocol,
            resolution = if (hostResolved) DnsResolutionState.OK else DnsResolutionState.FAILED,
            probes = probes,
            status = DnsDiagnosticsPolicy.status(hostResolved, probes),
            checkedAtMillis = System.currentTimeMillis()
        )
    }

    private fun candidatePorts(): List<PortCandidate> = when (config.protocol) {
        DnsProtocol.DOH -> listOf(PortCandidate(443, encrypted = true, label = "DoH (443)"))
        DnsProtocol.DOT -> listOf(PortCandidate(853, encrypted = true, label = "DoT (853)"))
        DnsProtocol.PLAINTEXT -> listOf(PortCandidate(53, encrypted = false, label = "純文字 (53)"))
        // Unconfirmed: measure both encrypted candidates and never imply that plaintext is acceptable.
        DnsProtocol.UNCONFIRMED -> listOf(
            PortCandidate(443, encrypted = true, label = "DoH (443)"),
            PortCandidate(853, encrypted = true, label = "DoT (853)")
        )
    }

    /** Tries the first few resolved addresses and keeps the best outcome. */
    private fun probeCandidate(
        host: String,
        addresses: List<InetAddress>,
        candidate: PortCandidate
    ): DnsEndpointProbe {
        var last: DnsEndpointProbe? = null
        for (address in addresses.take(MAX_ADDRESSES_TRIED)) {
            val probe = probe(host, address, candidate)
            if (probe.usable) return probe
            last = probe
        }
        return last ?: DnsEndpointProbe(
            label = candidate.label,
            port = candidate.port,
            tcpConnected = false,
            tlsHandshakeOk = false,
            usable = false,
            failure = DnsProbeFailure.CONNECTION_FAILED
        )
    }

    private fun probe(host: String, address: InetAddress, candidate: PortCandidate): DnsEndpointProbe {
        var tcpConnected = false
        var tlsHandshakeOk = false
        var failure: DnsProbeFailure? = null
        try {
            if (candidate.encrypted) {
                val socket = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket
                socket.use {
                    it.soTimeout = READ_TIMEOUT_MS
                    it.connect(InetSocketAddress(address, candidate.port), CONNECT_TIMEOUT_MS)
                    tcpConnected = true
                    it.sslParameters = it.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                        // Verify the certificate against the hostname even though we dialled an address.
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    it.startHandshake()
                    tlsHandshakeOk = it.session.isValid
                }
            } else {
                Socket().use {
                    it.connect(InetSocketAddress(address, candidate.port), CONNECT_TIMEOUT_MS)
                    tcpConnected = true
                }
            }
        } catch (timeout: SocketTimeoutException) {
            failure = DnsProbeFailure.TIMEOUT
        } catch (unverified: SSLPeerUnverifiedException) {
            failure = DnsProbeFailure.TLS_CERTIFICATE_REJECTED
        } catch (handshake: SSLHandshakeException) {
            failure = DnsProbeFailure.TLS_HANDSHAKE_FAILED
        } catch (certificate: CertificateException) {
            failure = DnsProbeFailure.TLS_CERTIFICATE_REJECTED
        } catch (unexpected: Exception) {
            failure = if (tcpConnected) {
                DnsProbeFailure.TLS_HANDSHAKE_FAILED
            } else {
                DnsProbeFailure.CONNECTION_FAILED
            }
        }

        val usable = tcpConnected && (!candidate.encrypted || tlsHandshakeOk)
        return DnsEndpointProbe(
            label = candidate.label,
            port = candidate.port,
            tcpConnected = tcpConnected,
            tlsHandshakeOk = tlsHandshakeOk,
            usable = usable,
            failure = if (usable) null else (failure ?: DnsProbeFailure.CONNECTION_FAILED)
        )
    }

    private data class PortCandidate(val port: Int, val encrypted: Boolean, val label: String)

    companion object {
        private const val TAG = "DnsDiagnostics"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_ADDRESSES_TRIED = 2
    }
}
