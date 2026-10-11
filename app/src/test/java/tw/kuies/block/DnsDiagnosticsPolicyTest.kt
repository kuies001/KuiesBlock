package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsDiagnosticsPolicy
import tw.kuies.block.dns.DnsEndpointProbe

class DnsDiagnosticsPolicyTest {

    private fun probe(usable: Boolean) = DnsEndpointProbe(
        label = "probe",
        port = 443,
        tcpConnected = usable,
        tlsHandshakeOk = usable,
        usable = usable,
        failure = null
    )

    @Test
    fun unresolvedHostIsAnError() {
        assertEquals(
            DnsCheckStatus.ERROR,
            DnsDiagnosticsPolicy.status(hostResolved = false, probes = listOf(probe(usable = true)))
        )
    }

    @Test
    fun oneUsableProbeIsOk() {
        assertEquals(
            DnsCheckStatus.OK,
            DnsDiagnosticsPolicy.status(hostResolved = true, probes = listOf(probe(false), probe(true)))
        )
    }

    @Test
    fun resolvedButNothingUsableIsAWarning() {
        assertEquals(
            DnsCheckStatus.WARNING,
            DnsDiagnosticsPolicy.status(hostResolved = true, probes = listOf(probe(false)))
        )
    }

    @Test
    fun noProbesMeansNothingWasChecked() {
        assertEquals(
            DnsCheckStatus.NOT_CHECKED,
            DnsDiagnosticsPolicy.status(hostResolved = true, probes = emptyList())
        )
    }
}
