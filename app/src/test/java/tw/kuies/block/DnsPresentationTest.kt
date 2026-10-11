package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsEndpointProbe
import tw.kuies.block.ui.DnsPresentation

class DnsPresentationTest {

    @Test
    fun tlsSuccessIsReportedExplicitly() {
        val probe = DnsEndpointProbe("DoH (443)", 443, true, true, true, "CN=dns.kuies.tw", null)
        assertEquals("可用（TLS 通過）", DnsPresentation.probeSummary(probe))
    }

    @Test
    fun connectionFailureNamesTheError() {
        val probe = DnsEndpointProbe("DoH (443)", 443, false, false, false, null, "ConnectException")
        val summary = DnsPresentation.probeSummary(probe)
        assertTrue(summary.contains("無法連線"))
        assertTrue(summary.contains("ConnectException"))
    }

    @Test
    fun tlsFailureIsDistinguishedFromConnectionFailure() {
        val probe = DnsEndpointProbe("DoT (853)", 853, true, false, false, null, "SSLHandshakeException")
        assertTrue(DnsPresentation.probeSummary(probe).contains("TLS 失敗"))
    }

    @Test
    fun everyStatusHasALabel() {
        DnsCheckStatus.entries.forEach { status ->
            assertTrue(DnsPresentation.statusLabel(status).isNotBlank())
        }
    }
}
