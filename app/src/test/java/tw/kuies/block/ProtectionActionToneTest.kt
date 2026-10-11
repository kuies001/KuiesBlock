package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.ui.ProtectionActionColorRole
import tw.kuies.block.ui.ProtectionActionTone
import tw.kuies.block.ui.ProtectionPresentation

/**
 * Locks down the single mapping that decides both the label and the colour of the home-screen
 * protection button, so 「啟用防護」 and 「停用防護」 can never look the same again.
 */
class ProtectionActionToneTest {

    private fun roleFor(state: ProtectionState, isServiceRunning: Boolean): ProtectionActionColorRole =
        ProtectionPresentation.actionColorRole(ProtectionPresentation.actionTone(state, isServiceRunning))

    @Test
    fun inactiveStatesOfferAnEnableActionInTheBrandPrimaryColour() {
        for (state in listOf(ProtectionState.NOT_AUTHORIZED, ProtectionState.AUTHORIZED_IDLE)) {
            assertEquals("state=$state", ProtectionActionTone.ENABLE, ProtectionPresentation.actionTone(state))
            assertEquals("state=$state", ProtectionActionColorRole.PRIMARY, roleFor(state, isServiceRunning = false))
        }
    }

    @Test
    fun startingOffersACancelActionInTheSoftSecondaryContainerColour() {
        assertEquals(ProtectionActionTone.CAUTION, ProtectionPresentation.actionTone(ProtectionState.STARTING))
        assertEquals(
            ProtectionActionColorRole.SECONDARY_CONTAINER,
            roleFor(ProtectionState.STARTING, isServiceRunning = true)
        )
    }

    @Test
    fun activeOffersAStopActionInTheErrorContainerColour() {
        assertEquals(ProtectionActionTone.DANGER, ProtectionPresentation.actionTone(ProtectionState.ACTIVE))
        assertEquals(
            ProtectionActionColorRole.ERROR_CONTAINER,
            roleFor(ProtectionState.ACTIVE, isServiceRunning = true)
        )
    }

    @Test
    fun erroredButStillRunningOffersAStopActionInTheErrorContainerColour() {
        assertEquals(
            ProtectionActionTone.DANGER,
            ProtectionPresentation.actionTone(ProtectionState.ERROR, isServiceRunning = true)
        )
        assertEquals(
            ProtectionActionColorRole.ERROR_CONTAINER,
            roleFor(ProtectionState.ERROR, isServiceRunning = true)
        )
    }

    @Test
    fun erroredAndStoppedOffersARetryActionInTheBrandPrimaryColour() {
        assertEquals(
            ProtectionActionTone.ENABLE,
            ProtectionPresentation.actionTone(ProtectionState.ERROR, isServiceRunning = false)
        )
        assertEquals(
            ProtectionActionColorRole.PRIMARY,
            roleFor(ProtectionState.ERROR, isServiceRunning = false)
        )
    }

    @Test
    fun theSpecifiedColourForEachOfTheFiveCases() {
        assertEquals(ProtectionActionColorRole.PRIMARY, roleFor(ProtectionState.NOT_AUTHORIZED, false))
        assertEquals(ProtectionActionColorRole.PRIMARY, roleFor(ProtectionState.AUTHORIZED_IDLE, false))
        assertEquals(ProtectionActionColorRole.SECONDARY_CONTAINER, roleFor(ProtectionState.STARTING, false))
        assertEquals(ProtectionActionColorRole.ERROR_CONTAINER, roleFor(ProtectionState.ACTIVE, false))
        assertEquals(ProtectionActionColorRole.ERROR_CONTAINER, roleFor(ProtectionState.ERROR, true))
        assertEquals(ProtectionActionColorRole.PRIMARY, roleFor(ProtectionState.ERROR, false))
    }

    @Test
    fun theLabelAndTheColourNeverDisagree() {
        for (state in ProtectionState.entries) {
            for (running in listOf(false, true)) {
                val label = ProtectionPresentation.primaryActionLabel(state, running)
                val tone = ProtectionPresentation.actionTone(state, running)
                when (label) {
                    "啟用防護", "重試啟用" ->
                        assertEquals("state=$state running=$running label=$label", ProtectionActionTone.ENABLE, tone)
                    "停用防護" -> assertTrue(
                        "state=$state running=$running label=$label",
                        tone == ProtectionActionTone.CAUTION || tone == ProtectionActionTone.DANGER
                    )
                    else -> throw AssertionError("unexpected action label: $label")
                }
            }
        }
    }

    @Test
    fun eachToneResolvesToItsOwnColourRole() {
        val roles = ProtectionActionTone.entries.map { ProtectionPresentation.actionColorRole(it) }
        assertEquals(roles.size, roles.toSet().size)
        assertEquals(ProtectionActionColorRole.PRIMARY, ProtectionPresentation.actionColorRole(ProtectionActionTone.ENABLE))
        assertEquals(
            ProtectionActionColorRole.SECONDARY_CONTAINER,
            ProtectionPresentation.actionColorRole(ProtectionActionTone.CAUTION)
        )
        assertEquals(
            ProtectionActionColorRole.ERROR_CONTAINER,
            ProtectionPresentation.actionColorRole(ProtectionActionTone.DANGER)
        )
    }

    @Test
    fun theToneIsAPureFunctionOfStateAndRunningFlag() {
        // Same inputs, same tone — there is no hidden time or animation dependency.
        repeat(3) {
            assertEquals(
                ProtectionActionTone.DANGER,
                ProtectionPresentation.actionTone(ProtectionState.ACTIVE, isServiceRunning = true)
            )
            assertEquals(
                ProtectionActionTone.ENABLE,
                ProtectionPresentation.actionTone(ProtectionState.AUTHORIZED_IDLE, isServiceRunning = false)
            )
        }
    }
}
