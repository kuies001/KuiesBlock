package tw.kuies.block.dns

/**
 * Pure mapping from raw diagnostic outcomes onto [DnsCheckStatus]. Kept free of Android types
 * so it can be unit tested on the JVM.
 */
object DnsDiagnosticsPolicy {

    fun status(hostResolved: Boolean, probes: List<DnsEndpointProbe>): DnsCheckStatus = when {
        !hostResolved -> DnsCheckStatus.ERROR
        probes.isEmpty() -> DnsCheckStatus.NOT_CHECKED
        probes.any { it.usable } -> DnsCheckStatus.OK
        else -> DnsCheckStatus.WARNING
    }
}
