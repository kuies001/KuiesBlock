package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.BuiltInFilterAttribution
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

class FilterProfileTest {

    @Test
    fun thereAreExactlyThreeProfilesWithDistinctIds() {
        assertEquals(3, FilterProfile.entries.size)
        assertEquals(
            FilterProfile.entries.size,
            FilterProfile.entries.map { it.id }.toSet().size
        )
        assertEquals(FilterProfile.RELAXED, FilterProfile.entries[0])
    }

    @Test
    fun standardIsTheDefaultAndAnUnknownIdFallsBackToIt() {
        assertEquals(FilterProfile.STANDARD, FilterProfile.DEFAULT)
        assertEquals(FilterProfile.STANDARD, FilterProfile.fromId(null))
        assertEquals(FilterProfile.STANDARD, FilterProfile.fromId(""))
        assertEquals(FilterProfile.STANDARD, FilterProfile.fromId("nonsense"))
        assertEquals(FilterProfile.STRICT, FilterProfile.fromId("strict"))
        assertEquals(FilterProfile.RELAXED, FilterProfile.fromId("relaxed"))
    }

    /** The three tiers are supersets of each other, so exactly one may ever be active. */
    @Test
    fun eachProfileNamesItsOwnSingleList() {
        val names = FilterProfile.entries.map { it.listName }
        assertEquals(names.size, names.toSet().size)
        assertTrue(FilterProfile.RELAXED.listName.contains("LIGHT"))
        assertTrue(FilterProfile.STANDARD.listName.contains("NORMAL"))
        assertTrue(FilterProfile.STRICT.listName.contains("PRO++"))
    }

    /** Verified against HaGeZi's own README: every URL must be the published https location. */
    @Test
    fun everyProfileUrlIsHttpsOnTheOfficialHost() {
        for (profile in FilterProfile.entries) {
            assertTrue("$profile primary must be https", profile.primaryUrl.startsWith("https://"))
            assertTrue("$profile fallback must be https", profile.fallbackUrl.startsWith("https://"))
            assertTrue(profile.primaryUrl.contains("hagezi/dns-blocklists"))
            assertTrue(profile.fallbackUrl.contains("hagezi/dns-blocklists"))
            assertTrue(profile.primaryUrl.endsWith(".txt"))
            assertNotEquals(profile.primaryUrl, profile.fallbackUrl)
        }
        assertEquals(
            FilterProfile.entries.size,
            FilterProfile.entries.map { it.primaryUrl }.toSet().size
        )
    }

    @Test
    fun theProfileUrlsMatchTheVerifiedUpstreamFileNames() {
        assertTrue(FilterProfile.RELAXED.primaryUrl.endsWith("/adblock/light.txt"))
        assertTrue(FilterProfile.STANDARD.primaryUrl.endsWith("/adblock/multi.txt"))
        assertTrue(FilterProfile.STRICT.primaryUrl.endsWith("/adblock/pro.plus.txt"))
    }

    @Test
    fun onlyTheStrictProfileIsAggressive() {
        assertFalse(FilterProfile.RELAXED.isAggressive)
        assertFalse(FilterProfile.STANDARD.isAggressive)
        assertTrue(FilterProfile.STRICT.isAggressive)
    }

    @Test
    fun theBuiltInSubscriptionDescribesTheActiveProfile() {
        val subscription = FilterSubscription.forProfile(FilterProfile.STRICT)
        assertEquals(FilterSubscription.PROFILE_SUBSCRIPTION_ID, subscription.id)
        assertEquals(FilterProfile.STRICT.listName, subscription.displayName)
        assertEquals(FilterProfile.STRICT.primaryUrl, subscription.url)
        assertEquals(SubscriptionOrigin.BUILT_IN, subscription.origin)
        assertTrue(subscription.enabled)
        assertEquals(SubscriptionState.NOT_READY, subscription.state)
        assertFalse("nothing downloaded yet", subscription.contributesRules)
    }

    @Test
    fun aSubscriptionOnlyContributesWhenEnabledReadyAndNonEmpty() {
        val ready = FilterSubscription.forProfile(FilterProfile.STANDARD)
            .copy(state = SubscriptionState.READY, ruleCount = 10)
        assertTrue(ready.contributesRules)
        assertFalse(ready.copy(enabled = false).contributesRules)
        assertFalse(ready.copy(state = SubscriptionState.FAILED).contributesRules)
        assertFalse(ready.copy(ruleCount = 0).contributesRules)
    }

    @Test
    fun attributionPointsAtTheUpstreamProject() {
        assertTrue(BuiltInFilterAttribution.HOME_PAGE.contains("hagezi/dns-blocklists"))
        assertTrue(BuiltInFilterAttribution.LICENCE_NAME.contains("General Public License"))
        assertTrue(BuiltInFilterAttribution.LICENCE_URL.startsWith("https://"))
    }
}
