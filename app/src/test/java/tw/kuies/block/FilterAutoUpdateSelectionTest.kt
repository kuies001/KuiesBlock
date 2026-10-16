package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.FilterAutoUpdateSelection
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

/**
 * Which sources an automatic sweep may touch.
 *
 * The five cases here are the five questions the rule has to answer, and the two that must never
 * happen are the ones that protect the user: a fresh install is never downloaded for, and a source the
 * user switched off is never refreshed. The one this test exists for is the recovery case — a source
 * whose first download failed used to be invisible to the sweep forever, because "has rules" was the
 * only test for "may be refreshed".
 */
class FilterAutoUpdateSelectionTest {

    private fun builtIn(
        state: SubscriptionState = SubscriptionState.READY,
        ruleCount: Int = 162_803,
        enabled: Boolean = true
    ) = FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
        state = state,
        ruleCount = ruleCount,
        enabled = enabled
    )

    private fun userAdded(
        state: SubscriptionState = SubscriptionState.READY,
        ruleCount: Int = 60_000,
        enabled: Boolean = true
    ) = FilterSubscription(
        id = "user.e5a463afbdc51006d2ff6c9f09823ca3",
        displayName = "我的清單",
        url = "https://example.invalid/list.txt",
        origin = SubscriptionOrigin.USER,
        enabled = enabled,
        state = state,
        ruleCount = ruleCount
    )

    @Test
    fun `an enabled source that already has rules is refreshed`() {
        assertTrue(FilterAutoUpdateSelection.shouldRefresh(builtIn()))
        assertTrue(FilterAutoUpdateSelection.shouldRefresh(userAdded()))
    }

    @Test
    fun `a fresh install is never downloaded for`() {
        // The v0.4.0 promise: the built-in list has no content and no attempt behind it until the user
        // picks a strength, so automatic updates (on by default) must leave it alone.
        assertFalse(
            FilterAutoUpdateSelection.shouldRefresh(
                builtIn(state = SubscriptionState.NOT_READY, ruleCount = 0)
            )
        )
    }

    @Test
    fun `a disabled source is never refreshed, even with rules`() {
        assertFalse(FilterAutoUpdateSelection.shouldRefresh(builtIn(enabled = false)))
        assertFalse(FilterAutoUpdateSelection.shouldRefresh(userAdded(enabled = false)))
        assertFalse(
            FilterAutoUpdateSelection.shouldRefresh(
                userAdded(state = SubscriptionState.FAILED, ruleCount = 0, enabled = false)
            )
        )
    }

    @Test
    fun `a user-added source whose first download failed is retried`() {
        // The recovery the defect removed: without this the source would sit empty until the user
        // opened settings and tapped 更新 again.
        assertTrue(
            FilterAutoUpdateSelection.shouldRefresh(
                userAdded(state = SubscriptionState.FAILED, ruleCount = 0)
            )
        )
    }

    @Test
    fun `a user-added source whose first download never finished is retried`() {
        assertTrue(
            FilterAutoUpdateSelection.shouldRefresh(
                userAdded(state = SubscriptionState.NOT_READY, ruleCount = 0)
            )
        )
    }

    @Test
    fun `a failed built-in list is retried, so a failed strength download can recover`() {
        assertTrue(
            FilterAutoUpdateSelection.shouldRefresh(
                builtIn(state = SubscriptionState.FAILED, ruleCount = 0)
            )
        )
    }

    @Test
    fun `a source with content keeps being refreshed after a later failure`() {
        // The updater keeps the stored list on failure, so ruleCount stays and the source stays eligible.
        assertTrue(
            FilterAutoUpdateSelection.shouldRefresh(
                builtIn(state = SubscriptionState.FAILED, ruleCount = 1024)
            )
        )
    }

    @Test
    fun `select keeps configuration order and drops everything else`() {
        val failedUserSource = userAdded(state = SubscriptionState.FAILED, ruleCount = 0)
        val settings = FilterSettings(
            subscriptions = listOf(
                builtIn(state = SubscriptionState.NOT_READY, ruleCount = 0),
                failedUserSource,
                userAdded(enabled = false)
            )
        )
        assertEquals(listOf(failedUserSource), FilterAutoUpdateSelection.select(settings))
    }
}
