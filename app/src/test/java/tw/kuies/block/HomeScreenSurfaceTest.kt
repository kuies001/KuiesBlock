package tw.kuies.block

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard for the v0.3.0 home screen.
 *
 * The home screen shows the protection status and nothing else: no DNS host, no DoH URL, no
 * transport, no counters, no Private-DNS wording. That contract is about which strings a Composable
 * can reach, and a plain JVM unit test cannot observe a Composable's output, so this test reads the
 * one file that draws the screen. It fails if any token that would surface DNS detail comes back, or
 * if the status wording stops being rendered.
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
