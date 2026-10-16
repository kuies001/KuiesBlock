package tw.kuies.block

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.core.ProtectionStatusHolder
import tw.kuies.block.data.AppSettings
import tw.kuies.block.data.AppSettingsStore
import tw.kuies.block.data.OnboardingGate
import tw.kuies.block.data.OnboardingState
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSettingsStore
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionState
import tw.kuies.block.vpn.VpnAuthorization

/**
 * Who is offered the first-run guide, what the consent dialog means, and what leaving the guide
 * writes.
 *
 * The important property is the negative one: an install that has been used at all — however that
 * shows up — is never offered the guide automatically, and a refusal is never recorded or shown as an
 * authorization. Leaving a replay writes nothing at all.
 */
class OnboardingGateTest {

    private val appPrefs = FakeSharedPreferences()
    private val appStore = AppSettingsStore(appPrefs)
    private val filterPrefs = FakeSharedPreferences()
    private val filterStore = FilterSettingsStore(filterPrefs)

    // --- who sees the guide -------------------------------------------------------------------

    @Test
    fun aGenuineNewInstallIsOfferedTheGuide() {
        assertTrue(
            OnboardingGate.shouldShowAutomatically(
                settings = appStore.load(),
                filterSettingsStored = filterStore.hasStoredSettings()
            )
        )
        // The order the app uses: the same store it wrote nothing to yet reports all four facts false.
        assertEquals(AppSettings(), appStore.load())
    }

    /**
     * An install upgrading from a build without this guide has no onboarding record either, so each
     * other sign of use has to be able to keep the guide away on its own.
     */
    @Test
    fun everySignOfPriorUseKeepsTheGuideAwayOnItsOwn() {
        val fresh = appStore.load()
        assertEquals(OnboardingState.NOT_SEEN, fresh.onboardingState)

        // The app was opened before, even if the permission was never granted: the record is there.
        assertFalse(
            OnboardingGate.shouldShowAutomatically(
                fresh.copy(vpnAuthorizationRecorded = true),
                filterSettingsStored = false
            )
        )
        assertFalse(
            OnboardingGate.shouldShowAutomatically(
                fresh.copy(vpnAuthorizationGranted = true),
                filterSettingsStored = false
            )
        )
        assertFalse(
            OnboardingGate.shouldShowAutomatically(
                fresh.copy(protectionStarted = true),
                filterSettingsStored = false
            )
        )
        assertFalse(
            OnboardingGate.shouldShowAutomatically(fresh, filterSettingsStored = true)
        )
    }

    @Test
    fun aGuideThatWasCompletedOrSkippedNeverComesBackByItself() {
        for (state in listOf(OnboardingState.COMPLETED, OnboardingState.SKIPPED)) {
            appStore.setOnboardingState(state)
            assertEquals(state, appStore.load().onboardingState)
            assertFalse(
                "state=$state",
                OnboardingGate.shouldShowAutomatically(appStore.load(), filterSettingsStored = false)
            )
        }
    }

    /**
     * A legacy 關閉 install is an existing user: its stored `enabled=false` is still stored settings,
     * and loading them must neither lose the chosen strength nor start anything by itself.
     */
    @Test
    fun aLegacyOffInstallKeepsItsStrengthAndIsNeverTreatedAsNew() {
        val legacy = FilterSettings.forProfile(FilterProfile.STRICT)
            .withEnabled(false)
            .withSubscription(
                FilterSubscription.forProfile(FilterProfile.STRICT).copy(
                    state = SubscriptionState.READY,
                    ruleCount = 211_887,
                    lastSuccessAtMillis = 1_700_000_000_000L
                )
            )
        filterStore.save(legacy)
        ProtectionStatusHolder.update(ProtectionStateRules.afterAuthorizationRevoked(), null)
        val stateBeforeLoad = ProtectionStatusHolder.state

        val loaded = filterStore.load()

        assertEquals("the chosen tier survives", FilterProfile.STRICT, loaded.profile)
        assertEquals("the downloaded list survives", 211_887, loaded.builtInSubscription!!.ruleCount)
        assertTrue("a legacy 關閉 loads as configured", loaded.enabled)
        assertEquals("loading must not start anything", stateBeforeLoad, ProtectionStatusHolder.state)
        assertFalse(
            "a legacy off install is an existing user, not a new one",
            OnboardingGate.shouldShowAutomatically(
                settings = appStore.load(),
                filterSettingsStored = filterStore.hasStoredSettings()
            )
        )
    }

    @Test
    fun anyStoredFilterSettingCountsAsAnExistingInstall() {
        assertFalse(filterStore.hasStoredSettings())

        filterStore.save(FilterSettings.forProfile(FilterProfile.RELAXED))

        assertTrue(filterStore.hasStoredSettings())
        assertFalse(OnboardingGate.shouldShowAutomatically(appStore.load(), filterStore.hasStoredSettings()))
    }

    // --- the consent dialog -------------------------------------------------------------------

    /**
     * The consent dialog's two outcomes, through the same calls the app makes: an OK is recorded as
     * granted and reaches `AUTHORIZED_IDLE`, and anything else — a refusal, a dismissal, a cancelled
     * dialog — is recorded as *not* granted and reaches `NOT_AUTHORIZED`.
     */
    @Test
    fun acceptedAndRefusedConsentAreRecordedAsWhatTheyAre() {
        val accepted = VpnAuthorization.consentGranted(Activity.RESULT_OK)
        assertTrue(accepted)
        appStore.setVpnAuthorizationGranted(accepted)
        assertTrue(appStore.load().vpnAuthorizationGranted)
        assertEquals(
            ProtectionState.AUTHORIZED_IDLE,
            ProtectionStateRules.afterAuthorizationRequest(accepted)
        )

        for (result in listOf(Activity.RESULT_CANCELED, Activity.RESULT_FIRST_USER)) {
            val granted = VpnAuthorization.consentGranted(result)
            assertFalse("result=$result must not be a grant", granted)
            appStore.setVpnAuthorizationGranted(granted)
            assertFalse(
                "a refusal must never be recorded as authorized",
                appStore.load().vpnAuthorizationGranted
            )
            assertEquals(
                ProtectionState.NOT_AUTHORIZED,
                ProtectionStateRules.afterAuthorizationRequest(granted)
            )
        }
    }

    // --- leaving the guide --------------------------------------------------------------------

    @Test
    fun finishingAndSkippingAreBothPersisted() {
        OnboardingGate.stateToPersist(replay = false, completed = true)
            ?.let(appStore::setOnboardingState)
        assertEquals(OnboardingState.COMPLETED, appStore.load().onboardingState)

        OnboardingGate.stateToPersist(replay = false, completed = false)
            ?.let(appStore::setOnboardingState)
        assertEquals(OnboardingState.SKIPPED, appStore.load().onboardingState)

        // A replay would write neither, which is what keeps a replay from consuming the record.
        assertNull(OnboardingGate.stateToPersist(replay = true, completed = true))
        assertNull(OnboardingGate.stateToPersist(replay = true, completed = false))
    }

    /**
     * A replay is read-only: it writes no onboarding state, so it can neither clear what the install
     * recorded nor make the automatic guide come back, and the stored settings are untouched.
     */
    @Test
    fun aReplayConsumesNothingAndLeavesEveryStoredValueAlone() {
        appStore.setVpnAuthorizationGranted(true)
        appStore.setProtectionStarted()
        appStore.setOnboardingState(OnboardingState.SKIPPED)
        filterStore.save(FilterSettings.forProfile(FilterProfile.STRICT))

        val appBefore = appPrefs.all.toMap()
        val filterBefore = filterPrefs.all.toMap()

        // Both ways out of a replayed guide take the same path.
        for (completed in listOf(true, false)) {
            OnboardingGate.stateToPersist(replay = true, completed = completed)
                ?.let(appStore::setOnboardingState)
        }

        assertEquals("a replay must write nothing", appBefore, appPrefs.all.toMap())
        assertEquals("a replay must clear nothing", filterBefore, filterPrefs.all.toMap())
        assertEquals(OnboardingState.SKIPPED, appStore.load().onboardingState)
        assertFalse(
            "a replay must not make the guide come back by itself",
            OnboardingGate.shouldShowAutomatically(appStore.load(), filterStore.hasStoredSettings())
        )
    }

    @Test
    fun protectionStartedIsRecordedOnceAndNeverReset() {
        assertFalse(appStore.load().protectionStarted)

        appStore.setProtectionStarted()

        assertTrue(appStore.load().protectionStarted)
        // A revoke writes the granted flag back to false; the "has been used" fact must survive it.
        appStore.setVpnAuthorizationGranted(false)
        assertTrue(appStore.load().protectionStarted)
    }

    @Test
    fun anUnknownStoredOnboardingStateFallsBackToNotSeen() {
        appPrefs.rawPut("onboarding_state", "wat")

        assertEquals(OnboardingState.NOT_SEEN, appStore.load().onboardingState)
    }
}
