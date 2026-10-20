package tw.kuies.block

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard for the v0.3.0 home screen.
 *
 * The home screen shows the protection status, the two benefit rows and nothing else: no DNS host,
 * no DoH URL, no transport, no counters, no Private-DNS wording. That contract is about which
 * strings a Composable can reach, and a plain JVM unit test cannot observe a Composable's output, so
 * this test reads the one file that draws the screen. It fails if any token that would surface DNS
 * detail comes back, or if the status wording stops being rendered.
 */
class HomeScreenSurfaceTest {

    /**
     * The home screen must never name the DNS server or describe how name resolution happens.
     */
    @Test
    fun theHomeScreenNeverMentionsTheDnsHostTheEndpointOrPrivateDns() {
        val source = homeScreenSource()
        val forbidden = listOf(
            "kuies.tw",
            "dns-query",
            "https://",
            "DoH",
            "DoT",
            "私人 DNS",
            "private dns"
        )
        forbidden.forEach { token ->
            assertFalse("HomeScreen.kt must not mention '$token'", source.contains(token, ignoreCase = true))
        }
    }

    /**
     * None of the DNS-status, Private-DNS, counter or interception presentation may reach the home
     * screen — that plumbing belongs to the settings screen or to the service.
     */
    @Test
    fun theHomeScreenConsumesNoDnsStatusOrCounterServices() {
        val source = homeScreenSource()
        val forbidden = listOf(
            "DnsPresentation",
            "DnsStatusPalette",
            "PrivateDns",
            "DnsPath",
            "DnsConnectionScenario",
            "DnsCheckStatus",
            "BlockStats",
            "DnsStatsRepository",
            "ProtectionPresentation.description",
            "ProtectionPresentation.interceptionLabel"
        )
        forbidden.forEach { token ->
            assertFalse("HomeScreen.kt must not use $token", source.contains(token))
        }
    }

    /**
     * ... but it must still show the status and the reason plus next step of a failure, or the
     * removal would have taken the protection state away with the DNS detail.
     */
    @Test
    fun theHomeScreenStillShowsTheStatusAndTheFailureExplanation() {
        val source = homeScreenSource()
        val required = listOf(
            "ProtectionPresentation.title",
            "ProtectionPresentation.errorReason",
            "ProtectionPresentation.errorAdvice",
            "ProtectionPresentation.primaryActionLabel"
        )
        required.forEach { token ->
            assertTrue("HomeScreen.kt should still render $token", source.contains(token))
        }
    }

    /**
     * The benefits block is drawn from its own strings and one icon per row, so a row cannot lose its
     * title, its explanation or its icon without this failing. It has no heading of its own.
     */
    @Test
    fun theHomeScreenRendersBothBenefitRowsWithTheirOwnIcon() {
        val source = homeScreenSource()
        val required = listOf(
            "R.string.home_benefit_blocking_title",
            "R.string.home_benefit_blocking_body",
            "R.string.home_benefit_free_title",
            "R.string.home_benefit_free_body",
            "R.drawable.ic_block",
            "R.drawable.ic_check_circle"
        )
        required.forEach { token ->
            assertTrue("HomeScreen.kt should render $token", source.contains(token))
        }
    }

    /**
     * ... and the per-app page is reachable from home itself, with the exact title and explanation
     * the requirement names, rather than only from settings.
     */
    @Test
    fun theHomeScreenOffersThePerAppEntry() {
        val source = homeScreenSource()
        val required = listOf(
            "R.string.home_app_settings_title",
            "R.string.home_app_settings_body",
            "R.drawable.ic_chevron_right",
            "onOpenAppProtection"
        )
        required.forEach { token ->
            assertTrue("HomeScreen.kt should render $token", source.contains(token))
        }
    }

    /**
     * The battery-exemption card sits exactly where the requirement puts it: after the per-app entry
     * and before the DNS backup card, so a later edit cannot slide it down the screen.
     */
    @Test
    fun theBatteryExemptionCardSitsBetweenThePerAppEntryAndDnsFallback() {
        val source = homeScreenSource()
        val perApp = source.indexOf("AppProtectionEntry(onOpen")
        val battery = source.indexOf("BatteryExemptionCard(")
        val dns = source.indexOf("DnsFallbackCard(")
        assertTrue("the home screen must offer the per-app entry first", perApp >= 0)
        assertTrue("the battery card must follow the per-app entry", battery > perApp)
        assertTrue("the DNS backup card must follow the battery card", dns > battery)
    }

    /** Both states are drawn: the request with its explanation, and the collapsed granted row. */
    @Test
    fun theHomeScreenDrawsTheBatteryExemptionInBothStates() {
        val source = homeScreenSource()
        val required = listOf(
            "R.string.home_background_title",
            "R.string.home_background_body",
            "R.string.home_background_pending",
            "R.string.home_background_action",
            "R.string.home_background_granted_description",
            "R.drawable.ic_check_circle",
            "R.drawable.ic_battery",
            "if (granted)"
        )
        required.forEach { token ->
            assertTrue("HomeScreen.kt should render $token", source.contains(token))
        }
    }

    /**
     * The granted row is one line and nothing else: keeping the explanation, the status or the button
     * in that branch would show a user who already allowed it something left to do.
     */
    @Test
    fun theGrantedBatteryRowHidesTheExplanationAndTheButton() {
        val card = homeScreenSource()
            .substringAfter("private fun BatteryExemptionCard")
            .substringBefore("private fun DnsFallbackCard")
        val grantedBranch = card.substringAfter("if (granted) {").substringBefore("} else {")
        val hidden = listOf(
            "home_background_body",
            "home_background_pending",
            "home_background_action",
            "Button("
        )
        hidden.forEach { token ->
            assertFalse("the granted row must not render $token", grantedBranch.contains(token))
        }
        assertTrue(
            "the granted row must state the exemption for screen readers",
            grantedBranch.contains("home_background_granted_description")
        )
        assertFalse(
            "the granted row must not draw the old text status",
            grantedBranch.contains("已允許") || grantedBranch.contains("home_background_granted\"")
        )
    }

    /** Resolves the file through the module directory the test task runs in. */
    private fun homeScreenSource(): String {
        val relative = "src/main/java/tw/kuies/block/ui/HomeScreen.kt"
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, relative), File(directory, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            }
            directory = directory.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }
}
