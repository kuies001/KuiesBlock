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

    @Test
    fun theSourceSectionNamesTheActiveTierAndItsMaintainer() {
        assertEquals("HaGeZi Multi NORMAL", FilterPresentation.mainListName(readyStatus(rules = 5)))
        assertEquals("HaGeZi", FilterPresentation.MAINTAINER)
        assertTrue(FilterPresentation.LICENCE, FilterPresentation.LICENCE.contains("GPL-3.0"))
    }

    @Test
    fun theEnabledFlagReadsAsEnabledOrDisabled() {
        assertEquals("已啟用", FilterPresentation.sourceEnabledLabel(true))
        assertEquals("已停用", FilterPresentation.sourceEnabledLabel(false))
    }

    @Test
    fun theRuleCountComesFromThePublishedSnapshotNotFromWhatTheListClaims() {
        // The subscription claims 161,902 rules, but only one rule is actually published.
        val label = FilterPresentation.effectiveRulesLabel(readyStatus(rules = 161_902))

        assertEquals("目前生效規則：1 條", label)
        assertEquals(
            "目前沒有生效中的規則",
            FilterPresentation.effectiveRulesLabel(
                FilterStatus.of(FilterSettings.DEFAULTS, FilterSnapshot.EMPTY)
            )
        )
    }

    @Test
    fun theLastSuccessStartsAtNeverAndStaysRelative() {
        val now = 1_000_000_000L

        assertEquals("從未成功更新", FilterPresentation.lastSuccessLabel(null, now))
        assertEquals("剛剛", FilterPresentation.lastSuccessLabel(now - 30_000, now))
        assertEquals("5 分鐘前", FilterPresentation.lastSuccessLabel(now - 5 * 60_000, now))
        assertEquals("3 小時前", FilterPresentation.lastSuccessLabel(now - 3 * 3_600_000, now))
        assertEquals("2 天前", FilterPresentation.lastSuccessLabel(now - 2 * 86_400_000, now))
    }

    @Test
    fun aFailedUpdateSaysThePreviousListIsStillInUse() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
            FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                state = SubscriptionState.FAILED,
                ruleCount = 5,
                lastSuccessAtMillis = 1_000L,
                lastError = "network error"
            )
        )
        val label = FilterPresentation.updateResultLabel(
            FilterStatus.of(settings, FilterSnapshot.EMPTY),
            now = 1_000_000L
        )

        assertTrue(label, label.contains("上次更新失敗"))
        assertTrue(label, label.contains("network error"))
        assertTrue(
            "a failed update must not read as having no protection",
            label.contains("仍使用上一次成功的清單")
        )
    }

    @Test
    fun updateResultReportsSuccessAndNotDownloadedYetWithoutContradictingItself() {
        val ready = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
            FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                state = SubscriptionState.READY,
                ruleCount = 5,
                lastSuccessAtMillis = 100_000L
            )
        )
        assertEquals(
            "上次更新成功（5 分鐘前）。",
            FilterPresentation.updateResultLabel(FilterStatus.of(ready, FilterSnapshot.EMPTY), now = 400_000L)
        )

        // Ready without a timestamp: still a success, but never the "從未成功更新" wording.
        val readyWithoutTime = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
            FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                state = SubscriptionState.READY,
                ruleCount = 5,
                lastSuccessAtMillis = null
            )
        )
        assertEquals(
            "上次更新成功。",
            FilterPresentation.updateResultLabel(
                FilterStatus.of(readyWithoutTime, FilterSnapshot.EMPTY),
                now = 400_000L
            )
        )

        assertEquals(
            "尚未下載清單。",
            FilterPresentation.updateResultLabel(
                FilterStatus.of(FilterSettings.DEFAULTS, FilterSnapshot.EMPTY),
                now = 400_000L
            )
        )
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
