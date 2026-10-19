package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kuies.block.dns.DnsFallbackDecision
import tw.kuies.block.dns.DnsFallbackMachine
import tw.kuies.block.dns.DnsFallbackPolicy
import tw.kuies.block.dns.DnsFallbackStatus
import tw.kuies.block.dns.DnsUpstreamRole

/** The primary/backup decision is pure, so it is tested with a controllable clock. */
class DnsFallbackMachineTest {

    private fun machine(enabled: Boolean = true) = DnsFallbackMachine(initiallyEnabled = enabled)

    private fun DnsFallbackMachine.failPrimary(times: Int, nowMillis: Long = 0L):
        DnsFallbackDecision {
        var decision = DnsFallbackDecision.NONE
        repeat(times) { decision = onQueryFailure(DnsUpstreamRole.PRIMARY, nowMillis) }
        return decision
    }

    private fun DnsFallbackMachine.moveToBackup(nowMillis: Long = 0L) {
        failPrimary(DnsFallbackPolicy.PRIMARY_SWITCH_THRESHOLD, nowMillis)
        onBackupProbeSuccess(nowMillis)
    }

    @Test
    fun aSingleFailureNeverMovesTheUpstream() {
        val machine = machine()

        assertEquals(DnsFallbackDecision.NONE, machine.failPrimary(1))
        assertEquals(DnsFallbackStatus.PRIMARY, machine.status)
        assertEquals(DnsUpstreamRole.PRIMARY, machine.activeRole)
    }

    @Test
    fun theBackupIsOnlyTriedAfterTheThresholdAndProvenByARoundTrip() {
        val machine = machine()
        machine.failPrimary(DnsFallbackPolicy.PRIMARY_SWITCH_THRESHOLD - 1)

        assertEquals(
            DnsFallbackDecision.TRY_BACKUP,
            machine.onQueryFailure(DnsUpstreamRole.PRIMARY, 0L)
        )
        assertEquals(DnsFallbackStatus.SWITCHING, machine.status)
        assertEquals(DnsUpstreamRole.PRIMARY, machine.activeRole)

        assertEquals(
            DnsFallbackDecision.SWITCHED_TO_BACKUP,
            machine.onBackupProbeSuccess(1_000L)
        )
        assertEquals(DnsFallbackStatus.BACKUP, machine.status)
        assertEquals(DnsUpstreamRole.BACKUP, machine.activeRole)
        assertEquals(
            DnsFallbackPolicy.RECOVERY_INITIAL_DELAY_MILLIS,
            machine.nextPrimaryProbeDelayMillis(1_000L)
        )
    }

    @Test
    fun aSuccessfulAnswerBreaksTheFailureStreak() {
        val machine = machine()
        machine.failPrimary(2)
        machine.onQuerySuccess(DnsUpstreamRole.PRIMARY)

        assertEquals(DnsFallbackDecision.NONE, machine.failPrimary(2))
        assertEquals(DnsFallbackStatus.PRIMARY, machine.status)
    }

    @Test
    fun aPrimarySuccessDuringTheBackupProbeKeepsTrafficOnThePrimary() {
        val machine = machine()
        machine.failPrimary(DnsFallbackPolicy.PRIMARY_SWITCH_THRESHOLD)
        assertEquals(DnsFallbackStatus.SWITCHING, machine.status)

        assertEquals(
            DnsFallbackDecision.CANCELLED_BACKUP_PROBE,
            machine.onQuerySuccess(DnsUpstreamRole.PRIMARY)
        )

        assertEquals(DnsFallbackStatus.PRIMARY, machine.status)
        assertEquals(DnsFallbackDecision.NONE, machine.onBackupProbeSuccess(1_000L))
        assertEquals(DnsUpstreamRole.PRIMARY, machine.activeRole)
        assertEquals(DnsFallbackStatus.PRIMARY, machine.status)
    }

    @Test
    fun aLatePrimaryFailureCannotDisturbTheBackup() {
        val machine = machine()
        machine.moveToBackup()

        assertEquals(
            DnsFallbackDecision.NONE,
            machine.onQueryFailure(DnsUpstreamRole.PRIMARY, 10L)
        )
        assertEquals(DnsFallbackStatus.BACKUP, machine.status)
        assertEquals(DnsUpstreamRole.BACKUP, machine.activeRole)
    }

    @Test
    fun repeatedBackupFailuresFailOpen() {
        val machine = machine()
        machine.moveToBackup()

        repeat(DnsFallbackPolicy.BACKUP_FAILURE_THRESHOLD - 1) {
            assertEquals(
                DnsFallbackDecision.NONE,
                machine.onQueryFailure(DnsUpstreamRole.BACKUP, 10L)
            )
        }
        assertEquals(
            DnsFallbackDecision.FAIL_OPEN,
            machine.onQueryFailure(DnsUpstreamRole.BACKUP, 10L)
        )
        assertEquals(DnsFallbackStatus.UNAVAILABLE, machine.status)
    }

    @Test
    fun aFailedBackupProbeFailsOpenInsteadOfClaimingABackup() {
        val machine = machine()
        machine.failPrimary(DnsFallbackPolicy.PRIMARY_SWITCH_THRESHOLD)

        assertEquals(
            DnsFallbackDecision.FAIL_OPEN,
            machine.onBackupProbeFailure()
        )
        assertEquals(DnsFallbackStatus.UNAVAILABLE, machine.status)
        assertEquals(DnsUpstreamRole.PRIMARY, machine.activeRole)
    }

    @Test
    fun recoveryProbesBackOffAndOnlyASuccessSwitchesBack() {
        val machine = machine()
        machine.moveToBackup(nowMillis = 0L)
        assertEquals(30_000L, machine.nextPrimaryProbeDelayMillis(0L))

        assertEquals(
            DnsFallbackDecision.RETRY_RECOVERY,
            machine.onPrimaryProbeFailure(30_000L)
        )
        assertEquals(30_000L, machine.nextPrimaryProbeDelayMillis(30_000L))
        assertEquals(DnsUpstreamRole.BACKUP, machine.activeRole)

        assertEquals(
            DnsFallbackDecision.RETRY_RECOVERY,
            machine.onPrimaryProbeFailure(60_000L)
        )
        assertEquals(60_000L, machine.nextPrimaryProbeDelayMillis(60_000L))

        assertEquals(
            DnsFallbackDecision.SWITCHED_TO_PRIMARY,
            machine.onPrimaryProbeSuccess()
        )
        assertEquals(DnsFallbackStatus.PRIMARY, machine.status)
        assertEquals(DnsUpstreamRole.PRIMARY, machine.activeRole)
        assertNull(machine.nextPrimaryProbeAtMillis)
    }

    @Test
    fun disablingWhileOnTheBackupNeedsAVerifiedPrimary() {
        val machine = machine()
        machine.moveToBackup()

        assertEquals(
            DnsFallbackDecision.VERIFY_PRIMARY_FOR_DISABLE,
            machine.setEnabled(false, 1_000L)
        )
        assertEquals(DnsFallbackStatus.SWITCHING, machine.status)
        assertEquals(DnsUpstreamRole.BACKUP, machine.activeRole)

        assertEquals(
            DnsFallbackDecision.DISABLED,
            machine.onDisableVerificationSuccess()
        )
        assertEquals(DnsFallbackStatus.DISABLED, machine.status)
        assertEquals(DnsUpstreamRole.PRIMARY, machine.activeRole)
    }

    @Test
    fun disablingWhileOnTheBackupFailsOpenIfThePrimaryIsStillDown() {
        val machine = machine()
        machine.moveToBackup()
        machine.setEnabled(false, 1_000L)

        assertEquals(
            DnsFallbackDecision.FAIL_OPEN,
            machine.onDisableVerificationFailure()
        )
        assertEquals(DnsFallbackStatus.UNAVAILABLE, machine.status)
    }

    @Test
    fun disabledFallbackUsesTheExistingPrimaryOnlyThreshold() {
        val machine = machine()
        assertEquals(DnsFallbackDecision.DISABLED, machine.setEnabled(false, 0L))

        repeat(DnsFallbackPolicy.PRIMARY_ONLY_FAILURE_THRESHOLD - 1) {
            assertEquals(
                DnsFallbackDecision.NONE,
                machine.onQueryFailure(DnsUpstreamRole.PRIMARY, 0L)
            )
        }
        assertEquals(
            DnsFallbackDecision.FAIL_OPEN,
            machine.onQueryFailure(DnsUpstreamRole.PRIMARY, 0L)
        )
        assertEquals(DnsFallbackStatus.UNAVAILABLE, machine.status)
    }

    @Test
    fun reEnablingRestoresTheBackupAndIgnoresTheCancelledDisableProbe() {
        val machine = machine()
        machine.moveToBackup()
        machine.setEnabled(false, 1_000L)

        assertEquals(
            DnsFallbackDecision.ENABLED,
            machine.setEnabled(true, 2_000L)
        )
        assertEquals(DnsFallbackStatus.BACKUP, machine.status)
        assertEquals(DnsUpstreamRole.BACKUP, machine.activeRole)
        assertEquals(
            DnsFallbackDecision.NONE,
            machine.onDisableVerificationSuccess()
        )
        assertEquals(DnsFallbackStatus.BACKUP, machine.status)
    }

    @Test
    fun startupMayBeginOnTheBackupButNeverWhenSwitchedOff() {
        val enabled = machine()
        assertEquals(
            DnsFallbackDecision.SWITCHED_TO_BACKUP,
            enabled.activateBackupAfterStartup(5_000L)
        )
        assertEquals(DnsUpstreamRole.BACKUP, enabled.activeRole)
        assertEquals(30_000L, enabled.nextPrimaryProbeDelayMillis(5_000L))

        val disabled = machine(enabled = false)
        assertEquals(
            DnsFallbackDecision.NONE,
            disabled.activateBackupAfterStartup(5_000L)
        )
        assertEquals(DnsUpstreamRole.PRIMARY, disabled.activeRole)
    }

    @Test
    fun startupKeepsBudgetForTheBackupInsteadOfSpendingItAllOnThePrimary() {
        val policy = DnsFallbackPolicy
        assertEquals(
            false,
            policy.shouldAttemptBackupAtStartup(
                attemptsMade = 1,
                primaryAttemptLimit = 3,
                remainingBudgetMillis = 14_000L
            )
        )
        assertEquals(
            true,
            policy.shouldAttemptBackupAtStartup(
                attemptsMade = 3,
                primaryAttemptLimit = 3,
                remainingBudgetMillis = 12_000L
            )
        )
        assertEquals(
            true,
            policy.shouldAttemptBackupAtStartup(
                attemptsMade = 1,
                primaryAttemptLimit = 3,
                remainingBudgetMillis = DnsFallbackPolicy.STARTUP_BACKUP_RESERVE_MILLIS
            )
        )
        assertEquals(
            false,
            policy.shouldAttemptBackupAtStartup(
                attemptsMade = 1,
                primaryAttemptLimit = 3,
                remainingBudgetMillis = 0L
            )
        )
    }
}
