package tw.kuies.block.vpn

/**
 * A bounded, monotonic budget for one protection startup sequence.
 *
 * The clock is injected so the deadline is unit testable without sleeping. A budget is created once
 * per start and must never be reset by an underlying-network change: a network flap must not be able
 * to extend the 「準備中」 wait indefinitely.
 */
class StartupBudget(
    val limitMillis: Long,
    private val clockMillis: () -> Long
) {
    private val startedAtMillis = clockMillis()

    fun elapsedMillis(): Long = (clockMillis() - startedAtMillis).coerceAtLeast(0L)

    fun isExhausted(): Boolean = elapsedMillis() >= limitMillis

    fun remainingMillis(): Long = (limitMillis - elapsedMillis()).coerceAtLeast(0L)
}

/**
 * Debug-only stage timings for one protection start.
 *
 * It records durations only — never DNS names, query contents, addresses, or payloads — so a debug
 * log built from it is safe to share. The clock is injected for deterministic tests.
 */
class StartupTimeline(private val clockMillis: () -> Long) {

    private val startedAtMillis = clockMillis()
    private val stages = linkedMapOf<String, Long>()

    /** Records the elapsed time since the start under [stage]; the first value per stage wins. */
    fun mark(stage: String) {
        if (stages.containsKey(stage)) return
        stages[stage] = (clockMillis() - startedAtMillis).coerceAtLeast(0L)
    }

    fun elapsedMillis(): Long = (clockMillis() - startedAtMillis).coerceAtLeast(0L)

    fun stageMillis(stage: String): Long? = stages[stage]

    fun summary(): String = stages.entries.joinToString(", ") { "${it.key}=${it.value}ms" }
}

/**
 * The rule that stops DNS verification retrying forever.
 *
 * Retrying is bounded by both an attempt cap and the total startup budget, so a slow or flapping
 * network can never leave the UI waiting without a decision.
 */
object StartupVerificationPolicy {

    fun mustStopRetrying(failedAttempts: Int, maxAttempts: Int, budgetExhausted: Boolean): Boolean =
        failedAttempts >= maxAttempts || budgetExhausted

    /** The delay before the next attempt, never longer than the startup budget still allows. */
    fun nextRetryDelayMillis(preferredMillis: Long, remainingBudgetMillis: Long): Long =
        minOf(preferredMillis, remainingBudgetMillis.coerceAtLeast(0L))
}
