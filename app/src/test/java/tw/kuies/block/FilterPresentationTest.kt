package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.filter.DomainSet
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSnapshot
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionState
import tw.kuies.block.ui.FilterPresentation

/**
 * What the settings screen may tell the user about the interception strength.
 *
 * The important property is the negative one: nothing may read as "in effect" while the platform's
 * Private DNS owns name resolution, and an untouched install must read as off rather than as
 * protection.
 */
class FilterPresentationTest {

    @Test
    fun anUntouchedInstallReadsAsOffRatherThanAsProtection() {
        val status = FilterStatus.of(FilterSettings.DEFAULTS, FilterSnapshot.EMPTY)

        assertEquals("未啟用", FilterPresentation.stateLabel(status, PrivateDnsMode.OFF))
    }

    @Test
    fun strictPrivateDnsIsNeverReportedAsInEffect() {
        val label = FilterPresentation.stateLabel(readyStatus(rules = 5), PrivateDnsMode.HOSTNAME)

        assertTrue(label, label.contains("已暫停"))
    }

    @Test
    fun loadedRulesReadAsInEffectWhenTheSystemHandsItsQueriesOver() {
        val label = FilterPresentation.stateLabel(readyStatus(rules = 5), PrivateDnsMode.OFF)

        assertTrue(label, label.contains("生效中"))
    }

    @Test
    fun aFailedListWithNothingStoredIsReportedAsAFailedUpdate() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
            FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                state = SubscriptionState.FAILED,
                ruleCount = 0,
                lastError = "network error"
            )
        )
        val label = FilterPresentation.stateLabel(
            FilterStatus.of(settings, FilterSnapshot.EMPTY),
            PrivateDnsMode.OFF
        )

        assertTrue(label, label.contains("更新失敗"))
    }

    @Test
    fun aDownloadInProgressIsSaidBeforeAnythingElse() {
        val label = FilterPresentation.stateLabel(readyStatus(rules = 5), PrivateDnsMode.HOSTNAME, busy = true)

        assertEquals("正在下載清單…", label)
    }

    @Test
    fun theOffChoiceIsWordedAsAChoice() {
        assertEquals("關閉", FilterPresentation.strengthLabel(null))
        assertEquals("標準（推薦）", FilterPresentation.strengthLabel(FilterProfile.STANDARD))
    }

    /** An enabled install whose stored list contributes [rules] rules. */
    private fun readyStatus(rules: Int): FilterStatus {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
            FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                state = SubscriptionState.READY,
                ruleCount = rules
            )
        )
        val snapshot = FilterSnapshot.build(
            subscriptionBlock = DomainSet.ofNormalised(listOf("ads.example"))
        )
        return FilterStatus.of(settings, snapshot)
    }
}
