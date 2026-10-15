package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.MatchScope
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

class FilterSettingsTest {

    private val customSource = FilterSubscription(
        id = "mine",
        displayName = "Mine",
        url = "https://lists.example.com/ads.txt",
        origin = SubscriptionOrigin.USER
    )

    @Test
    fun defaultsAreStandardWithOneBuiltInListAndNoManualRules() {
        val settings = FilterSettings.DEFAULTS

        assertEquals(FilterProfile.STANDARD, settings.profile)
        assertEquals(listOf(FilterSubscription.forProfile(FilterProfile.STANDARD)), settings.subscriptions)
        assertTrue(settings.manualAllow.isEmpty())
        assertTrue(settings.manualBlock.isEmpty())
        assertTrue(settings.etags.isEmpty())
        assertNull(settings.etagFor(FilterSubscription.PROFILE_SUBSCRIPTION_ID))
    }

    @Test
    fun forProfileKeepsTheMainListInStepWithTheTier() {
        val settings = FilterSettings.forProfile(FilterProfile.STRICT)
        val main = requireNotNull(settings.builtInSubscription)

        assertEquals(FilterProfile.STRICT, settings.profile)
        assertEquals(1, settings.subscriptions.size)
        assertEquals(FilterProfile.STRICT.primaryUrl, main.url)
        assertEquals(FilterProfile.STRICT.listName, main.displayName)
        assertEquals(SubscriptionOrigin.BUILT_IN, main.origin)
        assertEquals(SubscriptionState.NOT_READY, main.state)
        assertTrue(settings.customSubscriptions.isEmpty())
    }

    @Test
    fun switchingTheTierReplacesTheMainListAndKeepsCustomSources() {
        val start = FilterSettings.forProfile(FilterProfile.RELAXED)
            .withSubscription(customSource)
            .withEtag(customSource.id, "\"custom\"")
            .withEtag(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "\"tier\"")
            .withSubscription(
                FilterSubscription.forProfile(FilterProfile.RELAXED).copy(
                    state = SubscriptionState.READY,
                    ruleCount = 59_614,
                    lastSuccessAtMillis = 1_000L
                )
            )

        val switched = start.withProfile(FilterProfile.STRICT)
        val main = requireNotNull(switched.builtInSubscription)

        assertEquals(FilterProfile.STRICT, switched.profile)
        assertEquals(2, switched.subscriptions.size)
        assertEquals(FilterProfile.STRICT.primaryUrl, main.url)
        assertEquals(FilterProfile.STRICT.listName, main.displayName)
        assertEquals(
            "the previous tier's downloaded rules must not carry over",
            0,
            main.ruleCount
        )
        assertEquals(SubscriptionState.NOT_READY, main.state)
        assertNull(main.lastSuccessAtMillis)
        assertNull(switched.etagFor(FilterSubscription.PROFILE_SUBSCRIPTION_ID))
        assertEquals(listOf(customSource), switched.customSubscriptions)
        assertEquals("\"custom\"", switched.etagFor(customSource.id))
    }

    /** The main list is the active tier's own list, whatever identity a caller hands over. */
    @Test
    fun theMainListIsDefinedByTheActiveTier() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
            FilterSubscription(
                id = FilterSubscription.PROFILE_SUBSCRIPTION_ID,
                displayName = "some other list",
                url = FilterProfile.RELAXED.primaryUrl,
                origin = SubscriptionOrigin.USER,
                state = SubscriptionState.READY,
                ruleCount = 9
            )
        )
        val main = requireNotNull(settings.builtInSubscription)

        assertEquals(SubscriptionOrigin.BUILT_IN, main.origin)
        assertEquals(FilterProfile.STANDARD.primaryUrl, main.url)
        assertEquals(FilterProfile.STANDARD.listName, main.displayName)
        assertEquals("another list's bookkeeping must not be adopted", 0, main.ruleCount)
        assertEquals(SubscriptionState.NOT_READY, main.state)
    }

    @Test
    fun replacingASubscriptionWithTheSameUrlKeepsItsBookkeeping() {
        val stored = customSource.copy(
            state = SubscriptionState.FAILED,
            ruleCount = 12,
            lastSuccessAtMillis = 42L,
            lastError = "network error"
        )
        val settings = FilterSettings.DEFAULTS.withSubscription(stored)

        assertEquals(listOf(stored), settings.customSubscriptions)
    }

    @Test
    fun changingAsubscriptionUrlForgetsTheContentThatBelongedToTheOldOne() {
        val downloaded = customSource.copy(
            state = SubscriptionState.READY,
            ruleCount = 12,
            lastSuccessAtMillis = 42L
        )
        val settings = FilterSettings.DEFAULTS
            .withSubscription(downloaded)
            .withEtag(downloaded.id, "\"old\"")
            .withSubscription(downloaded.copy(url = "https://lists.example.com/other.txt"))

        val updated = settings.customSubscriptions.single()
        assertEquals("https://lists.example.com/other.txt", updated.url)
        assertEquals(SubscriptionState.NOT_READY, updated.state)
        assertEquals(0, updated.ruleCount)
        assertNull(updated.lastSuccessAtMillis)
        assertNull(settings.etagFor(downloaded.id))
    }

    @Test
    fun aUserSubscriptionCanBeRemovedButTheMainListCannot() {
        val settings = FilterSettings.DEFAULTS
            .withSubscription(customSource)
            .withEtag(customSource.id, "\"custom\"")

        val withoutCustom = settings.withoutSubscription(customSource.id)
        assertTrue(withoutCustom.customSubscriptions.isEmpty())
        assertNull(withoutCustom.etagFor(customSource.id))
        assertEquals(1, withoutCustom.subscriptions.size)

        val attempt = settings.withoutSubscription(FilterSubscription.PROFILE_SUBSCRIPTION_ID)
        assertEquals(settings, attempt)
    }

    @Test
    fun etagsAreRecordedAndForgotten() {
        val settings = FilterSettings.DEFAULTS.withEtag("id", "\"v1\"")
        assertEquals("\"v1\"", settings.etagFor("id"))
        assertNull(settings.withEtag("id", null).etagFor("id"))
        assertNull(settings.etagFor("unknown"))
    }

    @Test
    fun manualRulesKeepTheirScopeAndAreDeDuplicatedByCanonicalDomain() {
        val settings = FilterSettings.DEFAULTS
            .addManualAllow(ManualRule("Ads.Example.COM", MatchScope.EXACT))
            .addManualAllow(ManualRule("ads.example.com.", MatchScope.EXACT))
            .addManualAllow(ManualRule("other.example", MatchScope.SUBDOMAIN))
            .addManualBlock(ManualRule("blocked.example", MatchScope.EXACT))

        assertEquals(2, settings.manualAllow.size)
        assertEquals(ManualRule("Ads.Example.COM", MatchScope.EXACT), settings.manualAllow.first())
        assertEquals(listOf(ManualRule("blocked.example", MatchScope.EXACT)), settings.manualBlock)

        val removed = settings.removeManualAllow("ADS.example.com")
        assertEquals(listOf(ManualRule("other.example", MatchScope.SUBDOMAIN)), removed.manualAllow)
        assertEquals(
            "a manual block is untouched by removing an allow",
            settings.manualBlock,
            removed.manualBlock
        )
    }

    @Test
    fun aDisabledOrUnreadySourceDoesNotContributeButAFailedOneStillDoes() {
        val ready = customSource.copy(state = SubscriptionState.READY, ruleCount = 3)

        assertTrue(FilterSettings.DEFAULTS.contributesToSnapshot(ready))
        assertFalse(FilterSettings.DEFAULTS.contributesToSnapshot(ready.copy(enabled = false)))
        assertFalse(FilterSettings.DEFAULTS.contributesToSnapshot(ready.copy(ruleCount = 0)))
        assertFalse(
            FilterSettings.DEFAULTS.contributesToSnapshot(
                ready.copy(state = SubscriptionState.NOT_READY)
            )
        )
        assertFalse(
            FilterSettings.DEFAULTS.contributesToSnapshot(
                ready.copy(state = SubscriptionState.DISABLED)
            )
        )
        assertTrue(
            "a failed update keeps the rules that are already stored",
            FilterSettings.DEFAULTS.contributesToSnapshot(
                ready.copy(state = SubscriptionState.FAILED, lastError = "network error")
            )
        )
    }

    @Test
    fun onlyTheActiveTierOwnsTheMainList() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD)
        val main = requireNotNull(settings.builtInSubscription)

        assertTrue(settings.isProfileList(main))
        assertTrue(settings.isProfileList(main.copy(url = FilterProfile.STANDARD.fallbackUrl)))
        assertFalse(settings.isProfileList(main.copy(url = FilterProfile.STRICT.primaryUrl)))
        assertFalse(
            "a user source may not claim the main list slot",
            settings.isProfileList(main.copy(origin = SubscriptionOrigin.USER))
        )
    }
}
