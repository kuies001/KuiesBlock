package tw.kuies.block.dns

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Read-only DNS connectivity / TLS diagnostic for the configured endpoint.
 *
 * It resolves the hostname and then opens a short-lived TCP connection to each candidate
 * transport port. For an encrypted transport it also completes a TLS handshake (with SNI) and
 * records the presented certificate subject. It never sends DNS queries and never changes any
 * system configuration.
 *
 * Version 0.1.0 never establishes a VPN tunnel, so the diagnostic sockets do not need to be
 * excluded from a tunnel via `VpnService.protect`. When interception is added, this code must
 * run on sockets that are protected against the tunnel.
 *
 * Must be called from a background thread; it performs blocking network I/O.
 */
class DnsDiagnostics(private val config: DnsConfig) {

    fun run(): DnsDiagnosticReport {
        val host = config.host
        val resolution = runCatching {
            InetAddress.getAllByName(host).map { it.hostAddress ?: it.hostName }
        }
        val addresses = resolution.getOrDefault(emptyList())
        val resolutionError = resolution.exceptionOrNull()?.let { exception ->
            Log.w(TAG, "Resolving $host failed: ${exception.javaClass.simpleName}")
            exception.javaClass.simpleName
        }

        val hostResolved = addresses.isNotEmpty()
        val probes = if (hostResolved) candidatePorts().map { probe(host, it) } else emptyList()

        return DnsDiagnosticReport(
            host = host,
            protocol = config.protocol,
            resolvedAddresses = addresses,
            resolutionError = resolutionError,
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

    private fun probe(host: String, candidate: PortCandidate): DnsEndpointProbe {
        var tcpConnected = false
        var tlsHandshakeOk = false
        var certificateSubject: String? = null
        var errorMessage: String? = null
        try {
            if (candidate.encrypted) {
                val socket = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket
                socket.use {
                    it.soTimeout = READ_TIMEOUT_MS
                    it.connect(InetSocketAddress(host, candidate.port), CONNECT_TIMEOUT_MS)
                    tcpConnected = true
                    it.sslParameters = it.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                    }
                    it.startHandshake()
                    val session = it.session
                    tlsHandshakeOk = session.isValid
                    certificateSubject = runCatching { session.peerPrincipal.name }.getOrNull()
                }
            } else {
                Socket().use {
                    it.connect(InetSocketAddress(host, candidate.port), CONNECT_TIMEOUT_MS)
                    tcpConnected = true
                }
            }
        } catch (exception: Exception) {
            errorMessage = exception.javaClass.simpleName
        }

        return DnsEndpointProbe(
            label = candidate.label,
            port = candidate.port,
            tcpConnected = tcpConnected,
            tlsHandshakeOk = tlsHandshakeOk,
            usable = tcpConnected && (!candidate.encrypted || tlsHandshakeOk),
            certificateSubject = certificateSubject,
            errorMessage = errorMessage
        )
    }

    private data class PortCandidate(val port: Int, val encrypted: Boolean, val label: String)

    companion object {
        private const val TAG = "DnsDiagnostics"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
    }
}
