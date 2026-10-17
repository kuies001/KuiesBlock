package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSource
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

/** The mode isolation rules: presets use their own tier, custom mode uses the selection. */
class FilterModeTest {

    private fun ready(subscription: FilterSubscription, rules: Int = 1): FilterSubscription =
        subscription.copy(state = SubscriptionState.READY, ruleCount = rules)

    private val legacySource = FilterSubscription(
        id = "mine",
        displayName = "My old list",
        url = "https://lists.example.com/ads.txt",
        origin = SubscriptionOrigin.USER,
        state = SubscriptionState.READY,
        ruleCount = 3
    )

    @Test
    fun aPresetModeUsesOnlyItsOwnTierEvenWhenOtherSourcesAreStored() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD)
            .copy(
                selectedSources = setOf(FilterSource.OISD.id),
                subscriptions = listOf(
                    ready(FilterSubscription.forProfile(FilterProfile.STANDARD)),
                    ready(FilterSource.OISD.subscription()),
                    legacySource
                )
            )

        assertEquals(
            listOf(FilterSubscription.PROFILE_SUBSCRIPTION_ID),
            settings.effectiveSubscriptions.map { it.id }
        )
    }

    @Test
    fun customModeCombinesSelectedPresetsAndLegacyUserSources() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD)
            .copy(
                mode = FilterMode.CUSTOM,
                selectedSources = setOf(
                    FilterSource.HAGEZI_NORMAL.id,
                    FilterSource.HAGEZI_TIF.id
                ),
                subscriptions = listOf(
                    ready(FilterSubscription.forProfile(FilterProfile.STANDARD)),
                    ready(FilterSource.HAGEZI_TIF.subscription(), rules = 2),
                    legacySource
                )
            )

        assertEquals(
            setOf(FilterSubscription.PROFILE_SUBSCRIPTION_ID, FilterSource.HAGEZI_TIF.subscriptionId, "mine"),
            settings.effectiveSubscriptions.map { it.id }.toSet()
        )
    }

    @Test
    fun selectingASecondMultiTierReplacesTheFirst() {
        val base = FilterSettings.forProfile(FilterProfile.STANDARD).withMode(FilterMode.CUSTOM)

        val switched = base.withSourceSelected(FilterSource.HAGEZI_PRO_PLUS, true)

        assertEquals(FilterMode.CUSTOM, switched.mode)
        assertEquals(FilterProfile.STRICT, switched.profile)
        assertTrue(FilterSource.HAGEZI_PRO_PLUS.id in switched.selectedSources)
        assertFalse(FilterSource.HAGEZI_NORMAL.id in switched.selectedSources)
    }

    @Test
    fun theLastRemainingSourceCannotBeDeselected() {
        val base = FilterSettings.forProfile(FilterProfile.STANDARD)
            .withMode(FilterMode.CUSTOM)
            .copy(selectedSources = setOf(FilterSource.OISD.id))

        assertEquals(base, base.withSourceSelected(FilterSource.OISD, false))
    }

    @Test
    fun switchingToAPresetKeepsDownloadedRecordsAndTheCustomSelection() {
        val base = FilterSettings.forProfile(FilterProfile.STANDARD)
            .withMode(FilterMode.CUSTOM)
            .withSourceSelected(FilterSource.OISD, true)

        val switched = base.withProfile(FilterProfile.RELAXED)

        assertEquals(FilterMode.RELAXED, switched.mode)
        assertEquals(FilterProfile.RELAXED, switched.profile)
        assertTrue("the custom selection is kept for a later switch back", FilterSource.OISD.id in switched.selectedSources)
        assertEquals(
            setOf(FilterSubscription.PROFILE_SUBSCRIPTION_ID, FilterSource.OISD.subscriptionId),
            switched.subscriptions.map { it.id }.toSet()
        )
        assertEquals(
            "a preset still publishes only its own tier",
            listOf(FilterSubscription.PROFILE_SUBSCRIPTION_ID),
            switched.effectiveSubscriptions.map { it.id }
        )
    }
}
