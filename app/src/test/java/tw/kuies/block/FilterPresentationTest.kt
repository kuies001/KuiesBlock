package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * The important property is the negative one: nothing may read as "in effect" while protection is not
 * running or while the platform's Private DNS owns name resolution, and an untouched install must read
 * as "protection not started" rather than as protection.
 */
class FilterPresentationTest {

    @Test
    fun aFreshInstallWithProtectionOffNeverReadsAsProtection() {
        val status = FilterStatus.of(FilterSettings.DEFAULTS, FilterSnapshot.EMPTY)

        val label = FilterPresentation.stateLabel(status, PrivateDnsMode.OFF, protectionRunning = false)

        assertTrue(label, label.contains("防護未啟動"))
        assertFalse(label, label.contains("生效中"))
    }

    @Test
    fun strictPrivateDnsIsNeverReportedAsInEffect() {
        val label = FilterPresentation.stateLabel(
            readyStatus(rules = 5),
            PrivateDnsMode.HOSTNAME,
            protectionRunning = true
        )

        assertTrue(label, label.contains("已暫停"))
    }

    @Test
    fun loadedRulesReadAsInEffectWhenTheSystemHandsItsQueriesOver() {
        val label = FilterPresentation.stateLabel(
            readyStatus(rules = 5),
            PrivateDnsMode.OFF,
            protectionRunning = true
        )

        assertTrue(label, label.contains("生效中"))
    }

    /**
     * The whole point of the protection flag: the local filter is always configured now, so stored
     * rules alone must not be presented as rules that are doing something.
     */
    @Test
    fun storedRulesWithProtectionOffSayProtectionIsNotRunningRatherThanInEffect() {
        val status = readyStatus(rules = 5)

        val running = FilterPresentation.stateLabel(status, PrivateDnsMode.OFF, protectionRunning = true)
        val stopped = FilterPresentation.stateLabel(status, PrivateDnsMode.OFF, protectionRunning = false)

        assertTrue(running, running.contains("生效中"))
        assertTrue(stopped, stopped.contains("防護未啟動"))
        assertFalse("protection off must never read as being in effect", stopped.contains("生效中"))
        assertNotEquals(running, stopped)
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
            PrivateDnsMode.OFF,
            protectionRunning = true
        )

        assertTrue(label, label.contains("更新失敗"))
    }

    @Test
    fun aDownloadInProgressIsSaidBeforeAnythingElse() {
        val label = FilterPresentation.stateLabel(
            readyStatus(rules = 5),
            PrivateDnsMode.HOSTNAME,
            protectionRunning = false,
            busy = true
        )

        assertEquals("正在下載清單…", label)
    }

    @Test
    fun theStrengthListIsExactlyTheThreeTiersAndTheOffChoiceIsGone() {
        assertEquals("寬鬆", FilterPresentation.strengthLabel(FilterProfile.RELAXED))
        assertEquals("標準（推薦）", FilterPresentation.strengthLabel(FilterProfile.STANDARD))
        assertEquals("嚴格", FilterPresentation.strengthLabel(FilterProfile.STRICT))

        assertEquals(3, FilterProfile.entries.size)
        assertEquals(FilterProfile.STANDARD, FilterProfile.DEFAULT)
        for (profile in FilterProfile.entries) {
            val label = FilterPresentation.strengthLabel(profile)
            val detail = FilterPresentation.strengthDetail(profile)
            assertFalse("the 關閉 wording is gone", label.contains("關閉"))
            assertFalse("the 關閉 wording is gone", detail.contains("關閉"))
            assertFalse("nothing claims rules are not applied", detail.contains("不會套用"))
            assertTrue(label, label.isNotBlank())
            assertTrue(detail, detail.isNotBlank())
        }
    }

    /**
     * The 過濾器管理 row describes one source, so it must show that source's own state — showing the
     * local filter's master value there labelled a whole-filter state as one list's status.
     */
    @Test
    fun theSourceRowShowsTheListsOwnStateNotTheFilterMaster() {
        assertEquals("已啟用", FilterPresentation.mainListEnabledLabel(readyStatus(rules = 5)))

        val listSwitchedOff = FilterStatus.of(
            FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
                FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                    state = SubscriptionState.READY,
                    ruleCount = 5,
                    enabled = false
                )
            ),
            FilterSnapshot.EMPTY
        )

        assertTrue("the fixture must keep the filter itself configured", listSwitchedOff.enabled)
        assertEquals("已停用", FilterPresentation.mainListEnabledLabel(listSwitchedOff))
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
