package tw.kuies.block

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterPreferenceCodec
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSettingsStore
import tw.kuies.block.filter.FilterSource
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.MatchScope
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

class FilterSettingsStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = FilterSettingsStore(prefs)

    private val customSource = FilterSubscription(
        id = "mine",
        displayName = "My list",
        url = "https://lists.example.com/ads.txt",
        origin = SubscriptionOrigin.USER,
        state = SubscriptionState.FAILED,
        ruleCount = 42,
        lastSuccessAtMillis = 1_700_000_000_000L,
        lastError = "network error"
    )

    @Test
    fun nothingStoredLoadsTheDefaults() {
        val loaded = store.load()

        assertEquals(FilterSettings.DEFAULTS, loaded)
        assertEquals(FilterProfile.STANDARD, loaded.profile)
        assertTrue("the local filter is always configured now", loaded.enabled)
        assertEquals(1, loaded.subscriptions.size)
        assertTrue(loaded.customSubscriptions.isEmpty())
        assertTrue(loaded.manualAllow.isEmpty())
        assertTrue(loaded.manualBlock.isEmpty())
        assertTrue(loaded.etags.isEmpty())
    }

    /**
     * The migration the removal of the 關閉 option decided on (2026-10-16, option A).
     *
     * A build before that decision persisted `enabled=false` for the 關閉 choice. Loading it must
     * produce "configured" — never an off filter again, and never anything that could start the VPN
     * by itself: loading is a read, and protection is only ever started by the home switch.
     */
    @Test
    fun aLegacyStoredOffFlagLoadsAsConfiguredAndIsNeverHonouredAgain() {
        val legacy = FilterSettings.forProfile(FilterProfile.STANDARD)
            .withEnabled(false)
            .withSubscription(
                FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                    state = SubscriptionState.READY,
                    ruleCount = 161_902,
                    lastSuccessAtMillis = 1_700_000_000_000L
                )
            )
        store.save(legacy)
        assertFalse("the fixture must really hold the legacy off value", prefs.getBoolean("enabled", true))

        val loaded = store.load()

        assertTrue("a legacy 關閉 migrates to configured", loaded.enabled)
        assertEquals("the chosen tier is kept", FilterProfile.STANDARD, loaded.profile)
        assertEquals("the downloaded list is kept", 161_902, loaded.builtInSubscription!!.ruleCount)
        assertEquals(SubscriptionState.READY, loaded.builtInSubscription!!.state)
        assertEquals(1_700_000_000_000L, loaded.builtInSubscription!!.lastSuccessAtMillis ?: 0L)
    }

    @Test
    fun everythingRoundTripsThroughTheStore() {
        val settings = FilterSettings.forProfile(FilterProfile.STRICT)
            .withSubscription(
                FilterSettings.forProfile(FilterProfile.STRICT).builtInSubscription!!.copy(
                    state = SubscriptionState.READY,
                    ruleCount = 211_887,
                    lastSuccessAtMillis = 1_700_000_111_000L
                )
            )
            .withEtag(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "W/\"tier-1\"")
            .withSubscription(customSource)
            .withEtag(customSource.id, "\"custom-9\"")
            .withManualAllow(listOf(ManualRule("allowed.example", MatchScope.EXACT)))
            .withManualBlock(
                listOf(
                    ManualRule("blocked.example", MatchScope.SUBDOMAIN),
                    ManualRule("exact.example", MatchScope.EXACT)
                )
            )

        store.save(settings)

        assertEquals(settings, store.load())
    }

    @Test
    fun thePreferenceFileIsTheOneExcludedFromBackup() {
        val relative = "src/main/res/xml"
        val backup = readResourceFile("$relative/backup_rules.xml")
        val extraction = readResourceFile("$relative/data_extraction_rules.xml")
        val entry = "${FilterSettingsStore.PREFS_NAME}.xml"

        assertEquals("kuies_block_filter", FilterSettingsStore.PREFS_NAME)
        assertEquals(1, backup.split(entry).size - 1)
        assertEquals(2, extraction.split(entry).size - 1)
    }

    @Test
    fun anUnknownProfileIdFallsBackToStandard() {
        prefs.rawPut("profile", "ultra-mega")

        val loaded = store.load()

        assertEquals(FilterProfile.STANDARD, loaded.profile)
        assertEquals(FilterProfile.STANDARD.primaryUrl, loaded.builtInSubscription!!.url)
    }

    @Test
    fun aFieldOfTheWrongTypeFallsBackToItsDefaultWithoutLosingTheRest() {
        prefs.rawPut("profile", 42)
        prefs.rawPut("manual_allow", 7)
        prefs.rawPut("manual_block", "not a set")
        prefs.rawPut(
            "subscriptions",
            setOf(
                FilterPreferenceCodec.encode(
                    listOf(
                        "mine", "My list", "https://lists.example.com/ads.txt", "USER",
                        "1", "READY", "42", "1700000000000", "", "\"etag\""
                    )
                )
            )
        )

        val loaded = store.load()

        assertEquals(FilterProfile.STANDARD, loaded.profile)
        assertTrue(loaded.manualAllow.isEmpty())
        assertTrue(loaded.manualBlock.isEmpty())
        assertEquals(listOf(customSource.copy(state = SubscriptionState.READY, lastError = null)), loaded.customSubscriptions)
        assertEquals("\"etag\"", loaded.etagFor("mine"))
        assertEquals("the main list is restored even when its record is gone", 2, loaded.subscriptions.size)
        assertEquals(FilterProfile.STANDARD.primaryUrl, loaded.builtInSubscription!!.url)
    }

    @Test
    fun anUnreadableRecordIsSkippedWhileTheReadableOnesSurvive() {
        val good = FilterPreferenceCodec.encode(
            listOf("mine", "My list", "https://lists.example.com/ads.txt", "USER", "1", "READY", "3", "", "", "")
        )
        // A dangling/unknown escape cannot be decoded, so the whole record is unusable.
        val unknownEscape =
            "broken\u001FBroken\u001Fhttps://x.example/a.txt\u001FUSER\u001F1\u001FREADY\u001F1\u001F\u001F\u001Fbad\\escape"
        val tooFewFields = FilterPreferenceCodec.encode(listOf("short", "Short"))
        val noId = FilterPreferenceCodec.encode(
            listOf("", "No id", "https://x.example/b.txt", "USER", "1", "READY", "1", "", "", "")
        )
        val claimedBuiltInId = FilterPreferenceCodec.encode(
            listOf(
                FilterSubscription.PROFILE_SUBSCRIPTION_ID, "Fake tier", "https://evil.example/x.txt",
                "USER", "1", "READY", "9", "", "", ""
            )
        )
        prefs.rawPut(
            "subscriptions",
            setOf(good, unknownEscape, tooFewFields, noId, claimedBuiltInId)
        )

        val loaded = store.load()

        assertEquals(listOf("mine"), loaded.customSubscriptions.map { it.id })
        assertEquals(FilterProfile.STANDARD.primaryUrl, loaded.builtInSubscription!!.url)
        assertEquals(SubscriptionState.NOT_READY, loaded.builtInSubscription!!.state)
        assertEquals(0, loaded.builtInSubscription!!.ruleCount)
    }

    @Test
    fun unknownEnumValuesAndNumbersFallBackWithoutThrowing() {
        prefs.rawPut(
            "subscriptions",
            setOf(
                FilterPreferenceCodec.encode(
                    listOf("mine", "", "https://lists.example.com/ads.txt", "MARTIAN", "maybe", "SMASHED", "-4", "later", "", "")
                )
            )
        )
        prefs.rawPut(
            "manual_block",
            setOf(
                FilterPreferenceCodec.encode(listOf("blocked.example", "EVERYTHING")),
                FilterPreferenceCodec.encode(listOf("", "EXACT")),
                "not-a-record-with-any-separator"
            )
        )

        val loaded = store.load()
        val source = loaded.customSubscriptions.single()

        assertEquals(SubscriptionOrigin.USER, source.origin)
        assertEquals("the display name falls back to the id", "mine", source.displayName)
        assertTrue("an unreadable flag keeps the field's own default", source.enabled)
        assertEquals(SubscriptionState.NOT_READY, source.state)
        assertEquals(0, source.ruleCount)
        assertNull(source.lastSuccessAtMillis)
        assertEquals(listOf(ManualRule("blocked.example", MatchScope.SUBDOMAIN)), loaded.manualBlock)
    }

    @Test
    fun aStoredMainListFromAnotherTierIsResetRatherThanReused() {
        prefs.rawPut("profile", FilterProfile.STRICT.id)
        prefs.rawPut(
            "subscriptions",
            setOf(
                FilterPreferenceCodec.encode(
                    listOf(
                        FilterSubscription.PROFILE_SUBSCRIPTION_ID, FilterProfile.RELAXED.listName,
                        FilterProfile.RELAXED.primaryUrl, "BUILT_IN", "1", "READY", "59614",
                        "1700000000000", "", "\"relaxed\""
                    )
                )
            )
        )

        val loaded = store.load()
        val main = requireNotNull(loaded.builtInSubscription)

        assertEquals(FilterProfile.STRICT, loaded.profile)
        assertEquals(FilterProfile.STRICT.primaryUrl, main.url)
        assertEquals(FilterProfile.STRICT.listName, main.displayName)
        assertEquals(SubscriptionState.NOT_READY, main.state)
        assertEquals(0, main.ruleCount)
        assertNull(main.lastSuccessAtMillis)
        assertNull(loaded.etagFor(FilterSubscription.PROFILE_SUBSCRIPTION_ID))
    }

    @Test
    fun valuesContainingControlCharactersSurviveTheRoundTrip() {
        val awkward = "My\tlist\u001Fline\nbreak\\ \\."
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD)
            .withSubscription(
                FilterSubscription(
                    id = "mine",
                    displayName = awkward,
                    url = "https://lists.example.com/ads.txt?token=\u001F",
                    origin = SubscriptionOrigin.USER,
                    state = SubscriptionState.READY,
                    ruleCount = 1,
                    lastError = "odd\\error\u001Fline"
                )
            )
            .withEtag("mine", "W/\"a\\b\u001Fc\"")
            .withManualBlock(listOf(ManualRule("blocked.example", MatchScope.EXACT)))

        store.save(settings)

        assertEquals(settings, store.load())
    }

    @Test
    fun oneRecordPerSubscriptionIdIsStored() {
        val first = customSource
        val second = customSource.copy(displayName = "Renamed", ruleCount = 7)
        val settings = FilterSettings.DEFAULTS.withSubscription(first)

        store.save(settings.copy(subscriptions = settings.subscriptions + second))

        val loaded = store.load()
        assertEquals(1, loaded.customSubscriptions.size)
        assertEquals("the first record for an id wins", first.displayName, loaded.customSubscriptions.single().displayName)
    }

    @Test
    fun savingAnEmptySubscriptionListStillRestoresTheMainList() {
        store.save(FilterSettings.forProfile(FilterProfile.RELAXED).copy(subscriptions = emptyList()))

        val loaded = store.load()
        assertEquals(FilterProfile.RELAXED, loaded.profile)
        assertEquals(1, loaded.subscriptions.size)
        assertEquals(FilterProfile.RELAXED.primaryUrl, loaded.builtInSubscription!!.url)
    }

    @Test
    fun theLoadedOrderIsStableBecauseASetHasNone() {
        val zebra = customSource.copy(id = "zebra", displayName = "zebra list")
        val alpha = customSource.copy(id = "alpha", displayName = "Alpha list")
        val settings = FilterSettings.DEFAULTS
            .withSubscription(zebra)
            .withSubscription(alpha)
            .also {
                assertEquals(
                    "added in this order, which a set cannot preserve",
                    listOf(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "zebra", "alpha"),
                    it.subscriptions.map { subscription -> subscription.id }
                )
            }

        store.save(settings)

        val loaded = store.load()
        assertEquals(
            listOf(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "alpha", "zebra"),
            loaded.subscriptions.map { it.id }
        )
    }

    @Test
    fun anEnabledLegacyUserSourceMigratesToCustomModeAndKeepsTheTier() {
        prefs.rawPut("profile", FilterProfile.STRICT.id)
        prefs.rawPut(
            "subscriptions",
            setOf(
                FilterPreferenceCodec.encode(
                    listOf(
                        FilterSubscription.PROFILE_SUBSCRIPTION_ID, FilterProfile.STRICT.listName,
                        FilterProfile.STRICT.primaryUrl, "BUILT_IN", "1", "READY", "211887",
                        "1700000000000", "", "\"strict\""
                    )
                ),
                FilterPreferenceCodec.encode(
                    listOf("mine", "My list", "https://lists.example.com/ads.txt", "USER", "1", "READY", "42", "", "", "")
                )
            )
        )

        val loaded = store.load()

        assertEquals(FilterMode.CUSTOM, loaded.mode)
        assertEquals(FilterProfile.STRICT, loaded.profile)
        assertEquals(setOf(FilterSource.HAGEZI_PRO_PLUS.id), loaded.selectedSources)
        assertEquals(listOf("mine"), loaded.customSubscriptions.map { it.id })
        assertEquals(
            "the old effective combination keeps contributing in custom mode",
            setOf(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "mine"),
            loaded.effectiveSubscriptions.map { it.id }.toSet()
        )

        // Saving persists the migration, so a later launch cannot decide it again.
        store.save(loaded)
        val reloaded = store.load()
        assertEquals(FilterMode.CUSTOM, reloaded.mode)
        assertEquals(loaded.selectedSources, reloaded.selectedSources)
        assertEquals(FilterProfile.STRICT, reloaded.profile)
    }

    @Test
    fun aDeliberatePresetChoiceIsNotUndoneByALegacyUserSource() {
        store.save(FilterSettings.forProfile(FilterProfile.RELAXED).withSubscription(customSource))

        val loaded = store.load()

        assertEquals(FilterMode.RELAXED, loaded.mode)
        assertEquals(FilterProfile.RELAXED, loaded.profile)
        assertEquals("the legacy source is kept, not deleted", 1, loaded.customSubscriptions.size)
        assertEquals(
            "but a preset mode does not publish it",
            listOf(FilterSubscription.PROFILE_SUBSCRIPTION_ID),
            loaded.effectiveSubscriptions.map { it.id }
        )
    }

    @Test
    fun modeAndCustomSelectionRoundTrip() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD)
            .withMode(FilterMode.CUSTOM)
            .withSourceSelected(FilterSource.HAGEZI_TIF, true)
            .withSourceSelected(FilterSource.OISD, true)

        store.save(settings)
        val loaded = store.load()

        assertEquals(FilterMode.CUSTOM, loaded.mode)
        assertEquals(settings.selectedSources, loaded.selectedSources)
        assertTrue(loaded.subscriptions.any { it.id == FilterSource.HAGEZI_TIF.subscriptionId })
        assertTrue(loaded.subscriptions.any { it.id == FilterSource.OISD.subscriptionId })
    }

    private fun readResourceFile(relative: String): String {
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
