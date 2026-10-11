package tw.kuies.block.dns

/** Outcome of a DNS diagnostic pass. */
enum class DnsCheckStatus { NOT_CHECKED, CHECKING, OK, WARNING, ERROR }

/**
 * Result of probing one transport port of the DNS endpoint.
 *
 * [usable] is the single boolean the UI and policy care about: for an encrypted transport it
 * requires both a completed TCP connection and a successful TLS handshake; for a plaintext
 * transport only the TCP connection matters.
 */
data class DnsEndpointProbe(
    val label: String,
    val port: Int,
    val tcpConnected: Boolean,
    val tlsHandshakeOk: Boolean,
    val usable: Boolean,
    val certificateSubject: String?,
    val errorMessage: String?
)

/** Full, human-readable outcome of a DNS diagnostic run. */
data class DnsDiagnosticReport(
    val host: String,
    val protocol: DnsProtocol,
    val resolvedAddresses: List<String>,
    val resolutionError: String?,
    val probes: List<DnsEndpointProbe>,
    val status: DnsCheckStatus,
    val checkedAtMillis: Long
) {
    companion object {
        fun notChecked(host: String, protocol: DnsProtocol): DnsDiagnosticReport =
            DnsDiagnosticReport(
                host = host,
                protocol = protocol,
                resolvedAddresses = emptyList(),
                resolutionError = null,
                probes = emptyList(),
                status = DnsCheckStatus.NOT_CHECKED,
                checkedAtMillis = 0L
            )
    }
}
