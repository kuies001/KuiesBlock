package tw.kuies.block.dns

/** Outcome of a DNS diagnostic pass. */
enum class DnsCheckStatus { NOT_CHECKED, CHECKING, OK, WARNING, ERROR }

/** Whether the DoH hostname could be resolved at all. No resolved address is ever kept. */
enum class DnsResolutionState { NOT_ATTEMPTED, OK, FAILED }

/**
 * Why a transport probe failed.
 *
 * A category, never an exception message or an address, so nothing that reaches the UI can leak
 * the user's own server details.
 */
enum class DnsProbeFailure {
    CONNECTION_FAILED,
    TIMEOUT,
    TLS_HANDSHAKE_FAILED,
    TLS_CERTIFICATE_REJECTED
}

/**
 * Result of probing one transport port of the DNS endpoint.
 *
 * [usable] is the single boolean the policy cares about: for an encrypted transport it requires
 * both a completed TCP connection and a successful TLS handshake; for a plaintext transport only
 * the TCP connection matters.
 *
 * Deliberately holds no address and no certificate detail: the diagnostic UI must never be able to
 * show the user's own DNS server address.
 */
data class DnsEndpointProbe(
    val label: String,
    val port: Int,
    val tcpConnected: Boolean,
    val tlsHandshakeOk: Boolean,
    val usable: Boolean,
    val failure: DnsProbeFailure?
)

/**
 * Full outcome of a DNS diagnostic run.
 *
 * Contains no resolved addresses by construction, so no future UI change can accidentally display
 * the user's DNS server IP. Use [DnsDiagnostics]'s internal resolution for the DoH bootstrap.
 */
data class DnsDiagnosticReport(
    val host: String,
    val protocol: DnsProtocol,
    val resolution: DnsResolutionState,
    val probes: List<DnsEndpointProbe>,
    val status: DnsCheckStatus,
    val checkedAtMillis: Long
) {
    /** The first concrete transport failure, for a short and accurate UI explanation. */
    val primaryProbeFailure: DnsProbeFailure?
        get() = probes.firstNotNullOfOrNull { it.failure }

    companion object {
        fun notChecked(host: String, protocol: DnsProtocol): DnsDiagnosticReport =
            DnsDiagnosticReport(
                host = host,
                protocol = protocol,
                resolution = DnsResolutionState.NOT_ATTEMPTED,
                probes = emptyList(),
                status = DnsCheckStatus.NOT_CHECKED,
                checkedAtMillis = 0L
            )

        /** A report for a check that could not even run. */
        fun failed(host: String, protocol: DnsProtocol, checkedAtMillis: Long): DnsDiagnosticReport =
            DnsDiagnosticReport(
                host = host,
                protocol = protocol,
                resolution = DnsResolutionState.FAILED,
                probes = emptyList(),
                status = DnsCheckStatus.ERROR,
                checkedAtMillis = checkedAtMillis
            )
    }
}
