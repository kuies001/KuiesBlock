package tw.kuies.block

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsConfig
import tw.kuies.block.dns.DnsDiagnostics
import tw.kuies.block.dns.DnsProbeFailure
import tw.kuies.block.dns.DnsResolutionState
import tw.kuies.block.ui.DnsPresentation

/**
 * Exercises the diagnostic through a fake resolver, so the behaviour no longer depends on the
 * system resolver — which is exactly what Android's Private DNS takes over on a device.
 */
class DnsDiagnosticsTest {

    private val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

    @Test
    fun anUnresolvableHostIsAFailureAndNeverLooksConnected() {
        val report = DnsDiagnostics(DnsConfig()) { emptyList() }.run()

        assertEquals(DnsResolutionState.FAILED, report.resolution)
        assertEquals(DnsCheckStatus.ERROR, report.status)
        assertTrue(report.probes.isEmpty())
        assertEquals("連線異常", DnsPresentation.statusLabel(report.status))
    }

    @Test
    fun aResolvedHostWithNoWorkingTransportIsStillNotConnected() {
        val report = DnsDiagnostics(DnsConfig()) { listOf(InetAddress.getByName("127.0.0.1")) }.run()

        assertEquals(DnsResolutionState.OK, report.resolution)
        assertTrue(report.probes.isNotEmpty())
        assertFalse("loopback cannot serve a usable DoH endpoint here", report.probes.any { it.usable })
        assertEquals(DnsCheckStatus.WARNING, report.status)
        assertNotNull(report.primaryProbeFailure)
        assertTrue(
            report.primaryProbeFailure!! in setOf(
                DnsProbeFailure.CONNECTION_FAILED,
                DnsProbeFailure.TIMEOUT,
                DnsProbeFailure.TLS_HANDSHAKE_FAILED,
                DnsProbeFailure.TLS_CERTIFICATE_REJECTED
            )
        )
    }

    @Test
    fun nothingRenderedFromAReportCanLeakTheResolvedAddresses() {
        val report = DnsDiagnostics(DnsConfig()) {
            listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))
        }.run()

        val rendered = buildString {
            append(DnsPresentation.statusLabel(report.status))
            report.probes.forEach { append(DnsPresentation.probeSummary(it)) }
            append(DnsPresentation.resolutionFailureLabel())
        }
        assertFalse(rendered.contains("127.0.0.1"))
        assertFalse(rendered.contains("::1"))
        assertFalse(ipv4.containsMatchIn(rendered))
    }
}
