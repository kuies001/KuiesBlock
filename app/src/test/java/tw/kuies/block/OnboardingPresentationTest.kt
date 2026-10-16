package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.ui.OnboardingPage
import tw.kuies.block.ui.OnboardingPresentation
import tw.kuies.block.ui.ProtectionPresentation

/**
 * What the two guide pages say.
 *
 * The important properties are the negative ones: the first page never asks for a permission it
 * already holds and never claims one it does not, and the second page never reads as protection
 * unless the state is `ACTIVE` — a tunnel that merely started is not "filtering works".
 */
class OnboardingPresentationTest {

    // --- page 1: the VPN permission -----------------------------------------------------------

    @Test
    fun thePermissionPageCarriesItsOwnTitleBodyAndButton() {
        assertEquals("讓 KuiesBlock 幫你擋廣告", OnboardingPresentation.pageTitle(OnboardingPage.VPN_PERMISSION))
        assertTrue(
            OnboardingPresentation.pageBody(OnboardingPage.VPN_PERMISSION)
                .contains("在手機上處理 DNS 查詢並攔截廣告網域")
        )
        assertTrue(
            "the body says what the VPN is not",
            OnboardingPresentation.pageBody(OnboardingPage.VPN_PERMISSION)
                .contains("這不代表將所有網路流量傳送到遠端 VPN 伺服器")
        )
        assertEquals("授權 VPN", OnboardingPresentation.AUTHORIZE_ACTION)
    }

    @Test
    fun anAlreadyGrantedPermissionIsNotAskedForAgain() {
        assertEquals(
            OnboardingPresentation.AUTHORIZE_ACTION,
            OnboardingPresentation.permissionActionLabel(vpnAuthorized = false)
        )
        assertEquals(
            OnboardingPresentation.CONTINUE_ACTION,
            OnboardingPresentation.permissionActionLabel(vpnAuthorized = true)
        )
    }

    @Test
    fun thePermissionStatusOnlyClaimsWhatIsReallyHeld() {
        val granted = OnboardingPresentation.permissionStatus(vpnAuthorized = true)
        val missing = OnboardingPresentation.permissionStatus(vpnAuthorized = false)

        assertTrue(granted, granted.contains("已完成"))
        assertFalse(missing, missing.contains("已完成"))
        assertTrue(missing, missing.contains("需要這項授權"))
    }

    @Test
    fun theOtherVpnNoticeAppearsOnlyWhileAnotherVpnHoldsTheSlotAndNeverPromisesToStopIt() {
        assertNull(OnboardingPresentation.otherVpnNotice(anotherVpnActive = false))

        val notice = OnboardingPresentation.otherVpnNotice(anotherVpnActive = true)
        assertNotNull(notice)
        assertTrue(notice!!, notice.contains("其他 VPN"))
        assertTrue("it says KuiesBlock will not touch it", notice.contains("不會關閉"))
        assertTrue("it says KuiesBlock will not disable it", notice.contains("不會停用"))
    }

    // --- page 2: turning protection on --------------------------------------------------------

    @Test
    fun theProtectionPageCarriesItsOwnTitleBodyAndButton() {
        assertEquals("開啟防護，開始使用", OnboardingPresentation.pageTitle(OnboardingPage.START_PROTECTION))
        assertTrue(
            OnboardingPresentation.pageBody(OnboardingPage.START_PROTECTION)
                .contains("KuiesBlock 就會處理可攔截的 DNS 查詢")
        )
        assertTrue(
            "the body names the default strength",
            OnboardingPresentation.pageBody(OnboardingPage.START_PROTECTION).contains("標準模式")
        )
        assertEquals("開啟防護", OnboardingPresentation.START_ACTION)
    }

    @Test
    fun theStepLabelsSayThereAreExactlyTwoPages() {
        assertEquals("1／2", OnboardingPresentation.stepLabel(OnboardingPage.VPN_PERMISSION))
        assertEquals("2／2", OnboardingPresentation.stepLabel(OnboardingPage.START_PROTECTION))
    }

    /**
     * The claim the whole page rests on: nothing but a verified `ACTIVE` tunnel may read as enabled,
     * so a start in progress is told apart from both an idle and a working one.
     */
    @Test
    fun successIsOfferedOnlyOnceProtectionReallyRuns() {
        ProtectionState.entries.forEach { state ->
            for (running in listOf(false, true)) {
                val status = OnboardingPresentation.protectionStatus(state, running)
                val claimsEnabled = status.contains("防護已啟用")
                assertEquals("state=$state running=$running status=$status", state == ProtectionState.ACTIVE, claimsEnabled)

                val finishes = OnboardingPresentation.protectionActionLabel(state, running) ==
                    OnboardingPresentation.DONE_ACTION
                assertEquals("state=$state running=$running", state == ProtectionState.ACTIVE, finishes)
            }
        }
    }

    @Test
    fun aStartInProgressSaysSoAndItsButtonCannotBeTappedAgain() {
        assertEquals(
            OnboardingPresentation.STARTING_ACTION,
            OnboardingPresentation.protectionActionLabel(ProtectionState.STARTING)
        )
        // The status line is the app's own headline for that state, so the button and the line differ
        // only in punctuation — both say the start is in progress.
        val status = OnboardingPresentation.protectionStatus(ProtectionState.STARTING)
        assertEquals(ProtectionPresentation.title(ProtectionState.STARTING), status)
        assertTrue(status, status.contains("正在啟動防護"))
        assertFalse(OnboardingPresentation.protectionActionEnabled(ProtectionState.STARTING))

        ProtectionState.entries.filter { it != ProtectionState.STARTING }.forEach { state ->
            assertTrue("state=$state", OnboardingPresentation.protectionActionEnabled(state))
        }
    }

    @Test
    fun theIdleStatesOfferTheStartAndDeferTheRestOfTheWordingToTheApp() {
        for (state in listOf(ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE)) {
            assertEquals(
                "state=$state",
                OnboardingPresentation.START_ACTION,
                OnboardingPresentation.protectionActionLabel(state)
            )
        }
        // The status line is the app's own headline, never a second wording that could disagree.
        assertEquals(
            ProtectionPresentation.title(ProtectionState.AUTHORIZED_IDLE),
            OnboardingPresentation.protectionStatus(ProtectionState.AUTHORIZED_IDLE)
        )
    }

    /**
     * On failure the guide keeps the app's existing reason and next step, and offers the app's own
     * retry/stop action rather than inventing a fourth one.
     */
    @Test
    fun aFailureKeepsTheAppsOwnReasonAdviceAndAction() {
        for (error in ProtectionError.entries + null) {
            assertEquals(
                "error=$error",
                ProtectionPresentation.errorReason(error),
                OnboardingPresentation.protectionFailureReason(ProtectionState.ERROR, error)
            )
            assertEquals(
                "error=$error",
                ProtectionPresentation.errorAdvice(error),
                OnboardingPresentation.protectionFailureAdvice(ProtectionState.ERROR, error)
            )
        }

        // A failure is retryable (or stoppable while it still runs), so its button is live.
        assertTrue(OnboardingPresentation.protectionActionEnabled(ProtectionState.ERROR))
        assertEquals(
            ProtectionPresentation.primaryActionLabel(ProtectionState.ERROR, isServiceRunning = false),
            OnboardingPresentation.protectionActionLabel(ProtectionState.ERROR, isServiceRunning = false)
        )
        assertEquals(
            ProtectionPresentation.primaryActionLabel(ProtectionState.ERROR, isServiceRunning = true),
            OnboardingPresentation.protectionActionLabel(ProtectionState.ERROR, isServiceRunning = true)
        )
    }

    @Test
    fun onlyTheErrorStateHasAFailureToExplain() {
        for (state in ProtectionState.entries.filter { it != ProtectionState.ERROR }) {
            assertNull("state=$state", OnboardingPresentation.protectionFailureReason(state, null))
            assertNull("state=$state", OnboardingPresentation.protectionFailureAdvice(state, null))
        }
    }

    @Test
    fun theSkipActionIsAlwaysAvailableAndNamesItself() {
        assertEquals("略過", OnboardingPresentation.SKIP_ACTION)
    }

    /**
     * The guide is shown before anything is configured, so it stays as free of DNS detail as the home
     * screen: no host, no endpoint, no transport and no Private-DNS wording.
     */
    @Test
    fun theGuideCopyNeverNamesTheDnsServerTheEndpointOrPrivateDns() {
        val forbidden = listOf("kuies.tw", "dns-query", "https://", "DoH", "DoT", "私人 DNS", "Private DNS")
        val copy = OnboardingPage.entries.flatMap { page ->
            listOf(
                OnboardingPresentation.pageTitle(page),
                OnboardingPresentation.pageBody(page),
                OnboardingPresentation.stepLabel(page)
            )
        } + listOf(
            OnboardingPresentation.AUTHORIZE_ACTION,
            OnboardingPresentation.CONTINUE_ACTION,
            OnboardingPresentation.START_ACTION,
            OnboardingPresentation.DONE_ACTION,
            OnboardingPresentation.STARTING_ACTION,
            OnboardingPresentation.SKIP_ACTION,
            OnboardingPresentation.OTHER_VPN_NOTICE
        ) + ProtectionState.entries.flatMap { state ->
            listOf(
                OnboardingPresentation.protectionStatus(state),
                OnboardingPresentation.protectionActionLabel(state)
            )
        } + ProtectionError.entries.flatMap { error ->
            listOfNotNull(
                OnboardingPresentation.protectionFailureReason(ProtectionState.ERROR, error),
                OnboardingPresentation.protectionFailureAdvice(ProtectionState.ERROR, error)
            )
        }

        copy.forEach { text ->
            forbidden.forEach { token ->
                assertFalse("guide copy must not mention '$token': $text", text.contains(token))
            }
        }
    }

    @Test
    fun theGuideDefersToTheAppsWordingRatherThanRestatingIt() {
        // The guide renders the app's own headline rather than a second copy of it, so if that
        // wording changes the guide follows instead of drifting.
        assertEquals(
            ProtectionPresentation.title(ProtectionState.ACTIVE),
            OnboardingPresentation.protectionStatus(ProtectionState.ACTIVE)
        )
        assertEquals("防護已啟用", OnboardingPresentation.protectionStatus(ProtectionState.ACTIVE))
    }
}
