package tw.kuies.block.dns

/**
 * The situations the DNS status must tell apart.
 *
 * Kept separate from the protection state and from whether Private DNS is enabled, because those
 * are different questions: "can we reach the DNS server", "might Android resolve around us", and
 * "are we actually intercepting traffic".
 */
enum class DnsConnectionScenario {
    /** DoH works and the system is not resolving around KuiesBlock. */
    CONNECTED,

    /** DoH works, but Android's Private DNS may answer some names without KuiesBlock seeing them. */
    BYPASS_RISK,

    /** DoH, TLS, or name resolution actually failed. */
    FAILED,

    /** Nothing has been checked yet, or a check is still running. */
    UNKNOWN
}

object DnsConnectionScenarioPolicy {

    /**
     * An enabled Private DNS setting can only ever *add* the bypass warning: on its own it never
     * turns a working connection into a failure. A failure always stays a failure, so a real
     * UnknownHostException, DoH or TLS error can never be presented as "connected".
     */
    fun scenarioOf(
        status: DnsCheckStatus,
        mayBypassInterception: Boolean
    ): DnsConnectionScenario = when (status) {
        DnsCheckStatus.OK ->
            if (mayBypassInterception) DnsConnectionScenario.BYPASS_RISK else DnsConnectionScenario.CONNECTED
        DnsCheckStatus.WARNING, DnsCheckStatus.ERROR -> DnsConnectionScenario.FAILED
        DnsCheckStatus.NOT_CHECKED, DnsCheckStatus.CHECKING -> DnsConnectionScenario.UNKNOWN
    }
}
