package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsConnectionScenario
import tw.kuies.block.dns.DnsEndpointProbe
import tw.kuies.block.dns.DnsProbeFailure
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.dns.PrivateDnsSettings
import tw.kuies.block.ui.DnsPresentation

class DnsPresentationTest {

    private fun probe(
        tcpConnected: Boolean,
        tlsHandshakeOk: Boolean,
        usable: Boolean,
        failure: DnsProbeFailure?
    ) = DnsEndpointProbe(
        label = "DoH (443)",
        port = 443,
        tcpConnected = tcpConnected,
        tlsHandshakeOk = tlsHandshakeOk,
        usable = usable,
        failure = failure
    )

    private val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val ipv6 = Regex("""[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){2,}""")
    private val rawException = Regex("""[A-Za-z]+(Exception|Error)\b""")

    /** No rendered string may carry an address or a raw exception name. */
    private fun assertNoLeak(text: String) {
        assertFalse("leaked an IPv4 address: $text", ipv4.containsMatchIn(text))
        assertFalse("leaked an IPv6 address: $text", ipv6.containsMatchIn(text))
        assertFalse("leaked a raw exception name: $text", rawException.containsMatchIn(text))
    }

    @Test
    fun tlsSuccessIsReportedExplicitly() {
        assertEquals("可用（TLS 通過）", DnsPresentation.probeSummary(probe(true, true, true, null)))
    }

    @Test
    fun connectionFailureIsReportedWithoutRawExceptionText() {
        val summary = DnsPresentation.probeSummary(probe(false, false, false, DnsProbeFailure.CONNECTION_FAILED))
        assertTrue(summary.contains("無法連線"))
        assertNoLeak(summary)
    }

    @Test
    fun tlsFailureIsDistinguishedFromConnectionFailure() {
        val summary = DnsPresentation.probeSummary(
            probe(true, false, false, DnsProbeFailure.TLS_CERTIFICATE_REJECTED)
        )
        assertTrue(summary.contains("TLS 失敗"))
        assertTrue(summary.contains("TLS 憑證驗證失敗"))
        assertNoLeak(summary)
    }

    @Test
    fun everyStatusHasALabel() {
        DnsCheckStatus.entries.forEach { status ->
            assertTrue(DnsPresentation.statusLabel(status).isNotBlank())
        }
    }

    /**
     * The diagnostic wording is rendered on the settings screen. It may describe an outcome, but it
     * must not print the DNS host or the DoH endpoint: the endpoint stays internal to the app.
     */
    @Test
    fun noDiagnosticWordingPrintsTheHostOrTheEndpoint() {
        val forbidden = listOf("kuies.tw", "dns-query", "https://", "http://")
        val wording = buildList {
            DnsCheckStatus.entries.forEach { add(DnsPresentation.statusLabel(it)) }
            DnsProbeFailure.entries.forEach { add(DnsPresentation.probeFailureLabel(it)) }
            add(DnsPresentation.resolutionFailureLabel())
            DnsConnectionScenario.entries.forEach { add(DnsPresentation.scenarioLabel(it)) }
            PrivateDnsMode.entries.forEach { add(DnsPresentation.privateDnsLabel(it)) }
            add(
                DnsPresentation.privateDnsMatchLabel(
                    PrivateDnsSettings.stateOf("hostname", "dns.kuies.tw"),
                    "dns.kuies.tw"
                ).orEmpty()
            )
            add(DnsPresentation.probeSummary(probe(true, true, true, null)))
            add(DnsPresentation.probeSummary(probe(false, false, false, DnsProbeFailure.CONNECTION_FAILED)))
            DnsConnectionScenario.entries.forEach {
                add(DnsPresentation.scenarioLabel(it))
                add(DnsPresentation.scenarioShortHint(it).orEmpty())
            }
        }
        wording.forEach { text ->
            forbidden.forEach { token ->
                assertFalse("leaked '$token': $text", text.contains(token))
            }
            assertNoLeak(text)
        }
    }

    @Test
    fun everyProbeFailureHasAFriendlyLabel() {
        DnsProbeFailure.entries.forEach { failure ->
            val label = DnsPresentation.probeFailureLabel(failure)
            assertTrue("$failure has no label", label.isNotBlank())
            assertNoLeak(label)
        }
    }

    @Test
    fun everyScenarioHasALabelAndNoScenarioWordingLeaksAnything() {
        val privateDns = PrivateDnsSettings.stateOf("hostname", "dns.kuies.tw")
        DnsConnectionScenario.entries.forEach { scenario ->
            val label = DnsPresentation.scenarioLabel(scenario)
            assertTrue(label.isNotBlank())
            assertNoLeak(label)
            DnsPresentation.scenarioShortHint(scenario)?.let { assertNoLeak(it) }
            DnsPresentation.scenarioExplanation(scenario, privateDns, "dns.kuies.tw")
                ?.let { assertNoLeak(it) }
        }
        assertNoLeak(DnsPresentation.resolutionFailureLabel())
    }

    @Test
    fun theBypassExplanationNamesTheSameServerOnlyWhenItMatches() {
        val matching = PrivateDnsSettings.stateOf("hostname", "dns.kuies.tw")
        val different = PrivateDnsSettings.stateOf("hostname", "private.example")
        val explanation = DnsPresentation.scenarioExplanation(
            DnsConnectionScenario.BYPASS_RISK,
            matching,
            "dns.kuies.tw"
        )!!
        assertTrue(explanation.contains("可能直接處理部分查詢"))
        assertTrue(explanation.contains("攔截統計可能不完整"))
        assertTrue(explanation.contains("相同的 DNS 伺服器"))
        assertTrue(explanation.contains("可能不經過 KuiesBlock"))

        val plain = DnsPresentation.scenarioExplanation(
            DnsConnectionScenario.BYPASS_RISK,
            different,
            "dns.kuies.tw"
        )!!
        assertTrue(plain.contains("可能直接處理部分查詢"))
        assertFalse(plain.contains("相同的 DNS 伺服器"))
    }

    @Test
    fun thePrivateDnsMatchLabelNeverShowsTheHostname() {
        val matching = PrivateDnsSettings.stateOf("hostname", "dns.kuies.tw")
        assertEquals("與 KuiesBlock 相同", DnsPresentation.privateDnsMatchLabel(matching, "dns.kuies.tw"))
        val label = DnsPresentation.privateDnsMatchLabel(matching, "dns.kuies.tw")!!
        assertFalse(label.contains("kuies.tw"))
        assertNull(DnsPresentation.privateDnsMatchLabel(PrivateDnsSettings.stateOf("opportunistic", null), "dns.kuies.tw"))
        assertNull(DnsPresentation.privateDnsMatchLabel(PrivateDnsSettings.stateOf("off", null), "dns.kuies.tw"))
    }
}
