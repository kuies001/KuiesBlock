package tw.kuies.block

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.BuiltInSubscriptionHosts
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSource
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionOrigin

/** The source catalogue itself: URLs, attribution, ids and which tier a source maps to. */
class FilterSourceTest {

    @Test
    fun everySourceUsesHttpsAndCarriesAttribution() {
        for (source in FilterSource.entries) {
            assertTrue("${source.id} primary URL", source.primaryUrl.startsWith("https://"))
            assertTrue("${source.id} fallback URL", source.fallbackUrl.startsWith("https://"))
            assertTrue("${source.id} display name", source.displayName.isNotBlank())
            assertTrue("${source.id} purpose", source.purpose.isNotBlank())
            assertTrue("${source.id} maintainer", source.maintainer.isNotBlank())
            assertTrue("${source.id} maintainer home", source.maintainerHome.startsWith("https://"))
            assertTrue("${source.id} licence", source.licenceName.isNotBlank())
        }
    }

    @Test
    fun theThreeMultiTiersMapToTheThreePresetProfiles() {
        assertEquals(
            listOf(FilterProfile.RELAXED, FilterProfile.STANDARD, FilterProfile.STRICT),
            FilterSource.MULTI_TIERS.map { it.profile }
        )
        assertEquals(FilterSource.HAGEZI_LIGHT, FilterSource.forProfile(FilterProfile.RELAXED))
        assertEquals(FilterSource.HAGEZI_NORMAL, FilterSource.forProfile(FilterProfile.STANDARD))
        assertEquals(FilterSource.HAGEZI_PRO_PLUS, FilterSource.forProfile(FilterProfile.STRICT))
        for (source in FilterSource.MULTI_TIERS) {
            assertTrue(source.isMultiTier)
        }
        for (source in FilterSource.entries - FilterSource.MULTI_TIERS.toSet()) {
            assertFalse(source.isMultiTier)
            assertEquals(null, source.profile)
        }
    }

    @Test
    fun subscriptionIdsAreUniqueExceptForTheSharedProfileEntry() {
        val byId = FilterSource.entries.groupBy { it.subscriptionId }
        assertEquals(
            FilterSource.MULTI_TIERS.map { it.id }.toSet(),
            byId.getValue(FilterSubscription.PROFILE_SUBSCRIPTION_ID).map { it.id }.toSet()
        )
        for ((id, sources) in byId) {
            if (id == FilterSubscription.PROFILE_SUBSCRIPTION_ID) continue
            assertEquals("$id must identify exactly one source", 1, sources.size)
        }
    }

    @Test
    fun everySourceResolvesItsOwnStoredRecord() {
        for (source in FilterSource.entries) {
            val subscription = source.subscription()
            assertEquals(SubscriptionOrigin.BUILT_IN, subscription.origin)
            assertEquals(source.displayName, subscription.displayName)
            assertTrue("${source.id} did not resolve back to itself", source.matches(subscription))
            assertEquals(source, FilterSource.forSubscription(subscription))
        }
    }

    @Test
    fun everySourceBecomesEffectiveWhenTickedInCustomMode() {
        for (source in FilterSource.entries) {
            val settings = FilterSettings.forProfile(FilterProfile.STANDARD)
                .withMode(FilterMode.CUSTOM)
                .withSourceSelected(source, true)

            assertTrue("${source.id} is not selected", source.id in settings.selectedSources)
            assertTrue(
                "${source.id} has no active subscription",
                settings.effectiveSubscriptions.any { source.matches(it) }
            )
        }
    }

    @Test
    fun builtInHostGuardAllowsOnlyTheConfiguredSourceHost() {
        for (source in FilterSource.entries) {
            val primaryHost = URI(source.primaryUrl).host
            val fallbackHost = URI(source.fallbackUrl).host
            assertNotNull(primaryHost)
            assertNotNull(fallbackHost)
            assertEquals(setOf(primaryHost!!), BuiltInSubscriptionHosts.forUrl(source.primaryUrl))
            assertEquals(setOf(fallbackHost!!), BuiltInSubscriptionHosts.forUrl(source.fallbackUrl))
        }
    }
}
