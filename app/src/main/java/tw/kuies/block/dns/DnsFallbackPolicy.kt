package tw.kuies.block.dns

/** Which encrypted upstream currently answers the queries that escaped the local rules. */
enum class DnsUpstreamRole { PRIMARY, BACKUP }

/** What the home screen may say about the upstream, once protection is actually active. */
enum class DnsFallbackStatus { PRIMARY, BACKUP, SWITCHING, UNAVAILABLE, DISABLED }

/** What the service has to do after a state transition. */
enum class DnsFallbackDecision {
    NONE,
    TRY_BACKUP,
    FAIL_OPEN,
    VERIFY_PRIMARY_FOR_DISABLE,
    SWITCHED_TO_BACKUP,
    SWITCHED_TO_PRIMARY,
    CANCELLED_BACKUP_PROBE,
    DISABLED,
    ENABLED,
    RETRY_RECOVERY
}

/**
 * The thresholds and timings of the DNS backup path, in one place.
 *
 * They are constants rather than user settings on purpose: the user gets a single on/off switch,
 * and the numbers that decide when a working path is abandoned must be reviewable and testable.
 */
object DnsFallbackPolicy {

    /**
     * Consecutive failures before the backup is even tried. Deliberately above one: a single lost
     * packet or a momentary TLS hiccup must not move the user's DNS traffic to another provider.
     */
    const val PRIMARY_SWITCH_THRESHOLD = 3

    /** Consecutive failures while on the backup before protection fails open. */
    const val BACKUP_FAILURE_THRESHOLD = 3

    /** Consecutive failures with the automatic backup switched off; matches the old fail-open path. */
    const val PRIMARY_ONLY_FAILURE_THRESHOLD = 5

    /** First recovery probe after switching to the backup. */
    const val RECOVERY_INITIAL_DELAY_MILLIS = 30_000L

    /** Recovery probes back off up to this interval, so a long outage costs almost nothing. */
    const val RECOVERY_MAX_DELAY_MILLIS = 5 * 60_000L

    /**
     * Startup keeps this much of its total budget for trying the backup. A primary attempt that
     * would spend past it is abandoned instead, so adding a second upstream cannot make the home
     * screen sit on 「正在啟動防護」 longer than the existing budget.
     */
    const val STARTUP_BACKUP_RESERVE_MILLIS = 10_000L

    /** Whether the startup path should stop retrying the primary and try the backup instead. */
    fun shouldAttemptBackupAtStartup(
        attemptsMade: Int,
        primaryAttemptLimit: Int,
        remainingBudgetMillis: Long
    ): Boolean {
        if (remainingBudgetMillis <= 0L) return false
        return attemptsMade >= primaryAttemptLimit ||
            remainingBudgetMillis <= STARTUP_BACKUP_RESERVE_MILLIS
    }
}

/**
 * Pure state machine for the primary/backup upstream decision.
 *
 * It knows nothing about sockets, threads or Android: the service feeds it classified DoH outcomes
 * and the current time, and acts on the returned [DnsFallbackDecision]. That keeps the risky part —
 * when to leave a failing server, and when to trust it again — testable with a controllable clock.
 *
 * A failure is only counted for the role that is active when it arrives, so a slow request sent to
 * the old upstream cannot move or fail the path that replaced it.
 */
class DnsFallbackMachine(
    initiallyEnabled: Boolean,
    private val primarySwitchThreshold: Int = DnsFallbackPolicy.PRIMARY_SWITCH_THRESHOLD,
    private val backupFailureThreshold: Int = DnsFallbackPolicy.BACKUP_FAILURE_THRESHOLD,
    private val primaryOnlyFailureThreshold: Int =
        DnsFallbackPolicy.PRIMARY_ONLY_FAILURE_THRESHOLD,
    private val recoveryInitialDelayMillis: Long = DnsFallbackPolicy.RECOVERY_INITIAL_DELAY_MILLIS,
    private val recoveryMaxDelayMillis: Long = DnsFallbackPolicy.RECOVERY_MAX_DELAY_MILLIS
) {

    enum class PendingAction { NONE, PROBE_BACKUP, VERIFY_PRIMARY_FOR_DISABLE }

    var enabled: Boolean = initiallyEnabled
        private set

    var activeRole: DnsUpstreamRole = DnsUpstreamRole.PRIMARY
        private set

    var status: DnsFallbackStatus =
        if (initiallyEnabled) DnsFallbackStatus.PRIMARY else DnsFallbackStatus.DISABLED
        private set

    var pendingAction: PendingAction = PendingAction.NONE
        private set

    /** When the next low-frequency primary probe is due, or null when none is scheduled. */
    var nextPrimaryProbeAtMillis: Long? = null
        private set

    private var primaryFailures = 0
    private var backupFailures = 0
    private var disabledFailures = 0
    private var recoveryDelayMillis = recoveryInitialDelayMillis

    /** A valid response — including NXDOMAIN or any protocol-valid error rcode — proves the path. */
    @Synchronized
    fun onQuerySuccess(role: DnsUpstreamRole): DnsFallbackDecision {
        if (role != activeRole) return DnsFallbackDecision.NONE
        if (role == DnsUpstreamRole.PRIMARY) {
            primaryFailures = 0
            disabledFailures = 0
            // The primary answered while the backup was still being checked: stay on it and drop
            // the pending switch, so one late success cannot hand traffic to the other provider.
            if (status == DnsFallbackStatus.SWITCHING &&
                pendingAction == PendingAction.PROBE_BACKUP
            ) {
                pendingAction = PendingAction.NONE
                status = DnsFallbackStatus.PRIMARY
                return DnsFallbackDecision.CANCELLED_BACKUP_PROBE
            }
        } else {
            backupFailures = 0
        }
        return DnsFallbackDecision.NONE
    }

    /** A classified transport/protocol failure, never a valid DNS answer. */
    @Synchronized
    fun onQueryFailure(role: DnsUpstreamRole, nowMillis: Long): DnsFallbackDecision {
        if (role != activeRole) return DnsFallbackDecision.NONE

        if (!enabled) {
            disabledFailures++
            if (disabledFailures >= primaryOnlyFailureThreshold) {
                status = DnsFallbackStatus.UNAVAILABLE
                return DnsFallbackDecision.FAIL_OPEN
            }
            return DnsFallbackDecision.NONE
        }

        return when (activeRole) {
            DnsUpstreamRole.PRIMARY -> {
                if (pendingAction == PendingAction.PROBE_BACKUP) {
                    return DnsFallbackDecision.NONE
                }
                primaryFailures++
                if (primaryFailures >= primarySwitchThreshold) {
                    status = DnsFallbackStatus.SWITCHING
                    pendingAction = PendingAction.PROBE_BACKUP
                    DnsFallbackDecision.TRY_BACKUP
                } else {
                    DnsFallbackDecision.NONE
                }
            }
            DnsUpstreamRole.BACKUP -> {
                backupFailures++
                if (backupFailures >= backupFailureThreshold) {
                    status = DnsFallbackStatus.UNAVAILABLE
                    pendingAction = PendingAction.NONE
                    DnsFallbackDecision.FAIL_OPEN
                } else {
                    DnsFallbackDecision.NONE
                }
            }
        }
    }

    /** The backup answered a real DoH round trip, so it is safe to move the upstream to it. */
    @Synchronized
    fun onBackupProbeSuccess(nowMillis: Long): DnsFallbackDecision {
        if (pendingAction != PendingAction.PROBE_BACKUP) return DnsFallbackDecision.NONE
        if (!enabled || activeRole != DnsUpstreamRole.PRIMARY) {
            pendingAction = PendingAction.NONE
            status = if (enabled) DnsFallbackStatus.PRIMARY else DnsFallbackStatus.DISABLED
            return DnsFallbackDecision.NONE
        }
        switchToBackup(nowMillis)
        return DnsFallbackDecision.SWITCHED_TO_BACKUP
    }

    /** The backup also failed: there is no encrypted path left, so protection must fail open. */
    @Synchronized
    fun onBackupProbeFailure(): DnsFallbackDecision {
        if (pendingAction != PendingAction.PROBE_BACKUP) return DnsFallbackDecision.NONE
        pendingAction = PendingAction.NONE
        status = DnsFallbackStatus.UNAVAILABLE
        return DnsFallbackDecision.FAIL_OPEN
    }

    /** Startup reached the backup only after the primary could not be verified in time. */
    @Synchronized
    fun activateBackupAfterStartup(nowMillis: Long): DnsFallbackDecision {
        if (!enabled || activeRole != DnsUpstreamRole.PRIMARY) return DnsFallbackDecision.NONE
        switchToBackup(nowMillis)
        return DnsFallbackDecision.SWITCHED_TO_BACKUP
    }

    /** A low-frequency probe proved the primary works again. */
    @Synchronized
    fun onPrimaryProbeSuccess(): DnsFallbackDecision {
        if (!enabled || activeRole != DnsUpstreamRole.BACKUP) return DnsFallbackDecision.NONE
        if (status != DnsFallbackStatus.BACKUP) return DnsFallbackDecision.NONE
        activeRole = DnsUpstreamRole.PRIMARY
        status = DnsFallbackStatus.PRIMARY
        pendingAction = PendingAction.NONE
        nextPrimaryProbeAtMillis = null
        resetCounters()
        return DnsFallbackDecision.SWITCHED_TO_PRIMARY
    }

    /** The primary is still unavailable; back off instead of probing at a fixed rate forever. */
    @Synchronized
    fun onPrimaryProbeFailure(nowMillis: Long): DnsFallbackDecision {
        if (activeRole != DnsUpstreamRole.BACKUP || status != DnsFallbackStatus.BACKUP) {
            return DnsFallbackDecision.NONE
        }
        nextPrimaryProbeAtMillis = nowMillis + recoveryDelayMillis
        recoveryDelayMillis = (recoveryDelayMillis * 2).coerceAtMost(recoveryMaxDelayMillis)
        return DnsFallbackDecision.RETRY_RECOVERY
    }

    /** The user switched the feature off while on the backup, and the primary passed a round trip. */
    @Synchronized
    fun onDisableVerificationSuccess(): DnsFallbackDecision {
        if (enabled || pendingAction != PendingAction.VERIFY_PRIMARY_FOR_DISABLE) {
            return DnsFallbackDecision.NONE
        }
        activeRole = DnsUpstreamRole.PRIMARY
        status = DnsFallbackStatus.DISABLED
        pendingAction = PendingAction.NONE
        nextPrimaryProbeAtMillis = null
        resetCounters()
        return DnsFallbackDecision.DISABLED
    }

    /** The primary is unreachable after the user switched the backup off: fail open, never hide it. */
    @Synchronized
    fun onDisableVerificationFailure(): DnsFallbackDecision {
        if (enabled || pendingAction != PendingAction.VERIFY_PRIMARY_FOR_DISABLE) {
            return DnsFallbackDecision.NONE
        }
        status = DnsFallbackStatus.UNAVAILABLE
        pendingAction = PendingAction.NONE
        nextPrimaryProbeAtMillis = null
        return DnsFallbackDecision.FAIL_OPEN
    }

    /** The user changed the one switch. Turning it off from the backup requires a verified return. */
    @Synchronized
    fun setEnabled(value: Boolean, nowMillis: Long): DnsFallbackDecision {
        if (enabled == value) return DnsFallbackDecision.NONE
        enabled = value
        if (value) {
            pendingAction = PendingAction.NONE
            disabledFailures = 0
            if (activeRole == DnsUpstreamRole.BACKUP) {
                status = DnsFallbackStatus.BACKUP
                recoveryDelayMillis = recoveryInitialDelayMillis
                nextPrimaryProbeAtMillis = nowMillis + recoveryInitialDelayMillis
            } else {
                status = DnsFallbackStatus.PRIMARY
                nextPrimaryProbeAtMillis = null
            }
            return DnsFallbackDecision.ENABLED
        }

        primaryFailures = 0
        disabledFailures = 0
        nextPrimaryProbeAtMillis = null
        if (activeRole == DnsUpstreamRole.BACKUP) {
            status = DnsFallbackStatus.SWITCHING
            pendingAction = PendingAction.VERIFY_PRIMARY_FOR_DISABLE
            return DnsFallbackDecision.VERIFY_PRIMARY_FOR_DISABLE
        }
        pendingAction = PendingAction.NONE
        status = DnsFallbackStatus.DISABLED
        return DnsFallbackDecision.DISABLED
    }

    /** Milliseconds until the scheduled primary probe, or null when none is due. */
    @Synchronized
    fun nextPrimaryProbeDelayMillis(nowMillis: Long): Long? =
        nextPrimaryProbeAtMillis?.let { (it - nowMillis).coerceAtLeast(0L) }

    @Synchronized
    private fun switchToBackup(nowMillis: Long) {
        activeRole = DnsUpstreamRole.BACKUP
        status = DnsFallbackStatus.BACKUP
        pendingAction = PendingAction.NONE
        resetCounters()
        recoveryDelayMillis = recoveryInitialDelayMillis
        nextPrimaryProbeAtMillis = nowMillis + recoveryInitialDelayMillis
    }

    private fun resetCounters() {
        primaryFailures = 0
        backupFailures = 0
        disabledFailures = 0
    }
}
