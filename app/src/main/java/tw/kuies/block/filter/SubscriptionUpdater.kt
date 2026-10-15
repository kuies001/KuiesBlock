package tw.kuies.block.filter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Bounds for turning one downloaded list into rules. */
data class UpdateLimits(
    /** Most bytes accepted for one list, on top of the download cap. */
    val maxContentBytes: Long = 16L * 1024 * 1024,
    /** Budget for parsing one list. */
    val parseTimeoutMillis: Long = 10_000,
    /** Most rules accepted from one list; the parser reports anything beyond this as truncated. */
    val maxRules: Int = DnsRuleParser.MAX_RULES
) {
    companion object {
        val DEFAULT = UpdateLimits()
    }
}

/** One subscription update: which source, for which tier, and what is stored for it right now. */
data class SubscriptionUpdateRequest(
    val subscription: FilterSubscription,
    val profile: FilterProfile,
    val etag: String? = null
)

/**
 * What one update attempt produced.
 *
 * Every outcome carries the subscription's new bookkeeping, so the caller persists exactly what the
 * attempt recorded — including [Failed], whose state and short reason have to survive a restart.
 */
sealed interface SubscriptionUpdateResult {

    /** New content is stored and parsed. */
    data class Updated(val subscription: FilterSubscription, val etag: String?) : SubscriptionUpdateResult

    /** The stored copy is still current (HTTP 304); nothing was transferred. */
    data class Unchanged(val subscription: FilterSubscription) : SubscriptionUpdateResult

    /** The attempt failed; the previous content and snapshot stay in use. */
    data class Failed(
        val reason: SubscriptionFailure,
        val subscription: FilterSubscription
    ) : SubscriptionUpdateResult

    /** An update for this subscription was already running. The second request is dropped. */
    data object SkippedInFlight : SubscriptionUpdateResult
}

/** Persistence hook: called with the new bookkeeping of a subscription after every attempt. */
fun interface SubscriptionStateWriter {
    fun onState(subscriptionId: String, subscription: FilterSubscription, etag: String?)

    companion object {
        /** For callers that persist the returned result themselves. */
        val NONE = SubscriptionStateWriter { _, _, _ -> }
    }
}

/**
 * Turns a fetch into stored, usable rules.
 *
 * Guarantees, in the order they matter:
 *  * **Single flight per subscription.** A second update for an id already being updated is reported
 *    as [SubscriptionUpdateResult.SkippedInFlight] and never queued, so a burst of taps cannot start
 *    several downloads of the same list.
 *  * **Atomic.** The text is staged, parsed and only then does it replace the stored copy. Any failure
 *    (network, size, truncated or unparseable list, disk) keeps the previous content, the previous
 *    snapshot, and records [SubscriptionState.FAILED] with a short reason.
 *  * **Bounded.** Caps on bytes, on parse time and on rule count; a truncated parse is a failure, never
 *    a partially accepted list.
 *  * **Never on the packet path.** The caller supplies the [dispatcher] this class posts to; a
 *    same-thread dispatcher (`Executor { it.run() }`) is fine in tests.
 *
 * One instance per process: the in-flight set is what makes the single-flight guarantee work.
 */
class SubscriptionUpdater(
    private val source: SubscriptionSource,
    private val contentStore: SubscriptionContentStore,
    private val dispatcher: Executor,
    private val stateWriter: SubscriptionStateWriter = SubscriptionStateWriter.NONE,
    private val limits: UpdateLimits = UpdateLimits.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Runs the update on the calling thread and returns its outcome.
     *
     * Call it from a background dispatcher; it performs network and disk I/O.
     */
    fun update(request: SubscriptionUpdateRequest): SubscriptionUpdateResult {
        val id = request.subscription.id
        if (!inFlight.add(id)) return SubscriptionUpdateResult.SkippedInFlight
        return try {
            runGuarded(request)
        } finally {
            inFlight.remove(id)
        }
    }

    /**
     * Posts the update to [dispatcher].
     *
     * Returns false — without doing anything at all — when an update for that subscription is already
     * running, and true once the work has been handed over. The outcome is delivered to [onResult] on
     * the dispatcher's thread.
     */
    fun schedule(
        request: SubscriptionUpdateRequest,
        onResult: (SubscriptionUpdateResult) -> Unit
    ): Boolean {
        val id = request.subscription.id
        if (!inFlight.add(id)) return false

        val task = Runnable {
            val result = try {
                runGuarded(request)
            } finally {
                inFlight.remove(id)
            }
            try {
                onResult(result)
            } catch (_: Exception) {
                // A reporting failure must not look like an update failure.
            }
        }

        return try {
            dispatcher.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            inFlight.remove(id)
            false
        }
    }

    private fun runGuarded(request: SubscriptionUpdateRequest): SubscriptionUpdateResult = try {
        runUpdate(request)
    } catch (_: Exception) {
        // Whatever went wrong, the previous content and snapshot stay valid.
        failed(request, request.subscription, SubscriptionFailure.IO)
    }

    private fun runUpdate(request: SubscriptionUpdateRequest): SubscriptionUpdateResult {
        val subscription = request.subscription
        val allowedHosts = if (subscription.origin == SubscriptionOrigin.BUILT_IN) {
            BuiltInSubscriptionHosts.forProfile(request.profile)
        } else {
            emptySet()
        }

        val fetched = source.fetch(
            SubscriptionFetchRequest(
                subscriptionId = subscription.id,
                url = subscription.url,
                origin = subscription.origin,
                allowedHosts = allowedHosts,
                etag = request.etag
            )
        )

        return when (fetched) {
            SubscriptionFetchResult.NotModified -> {
                // The stored copy is confirmed current, so whatever is on disk stays in use.
                val confirmed = subscription.copy(
                    state = if (subscription.ruleCount > 0) {
                        SubscriptionState.READY
                    } else {
                        subscription.state
                    },
                    lastSuccessAtMillis = clock(),
                    lastError = null
                )
                record(subscription.id, confirmed, request.etag)
                SubscriptionUpdateResult.Unchanged(confirmed)
            }

            is SubscriptionFetchResult.Failure -> failed(request, subscription, fetched.reason)

            is SubscriptionFetchResult.Success -> applySuccess(request, fetched)
        }
    }

    private fun applySuccess(
        request: SubscriptionUpdateRequest,
        fetched: SubscriptionFetchResult.Success
    ): SubscriptionUpdateResult {
        val subscription = request.subscription
        if (fetched.byteCount > limits.maxContentBytes) {
            return failed(request, subscription, SubscriptionFailure.TOO_LARGE)
        }

        val parseStartedAt = clock()
        val parsed = DnsRuleParser.parse(fetched.text, limits.maxRules)
        val parseMillis = clock() - parseStartedAt

        // Checked before anything is stored: a truncated list would silently protect the user with
        // fewer rules than the list has, which is worse than keeping the previous copy.
        if (parsed.stats.truncated) {
            return failed(request, subscription, SubscriptionFailure.TRUNCATED_LIST)
        }
        // The parser has no time hook, so the budget is checked after it returns: an over-budget list
        // is simply not accepted. The byte and rule caps bound the work that can reach this point.
        if (parseMillis > limits.parseTimeoutMillis) {
            return failed(request, subscription, SubscriptionFailure.PARSE_TIMEOUT)
        }
        if (parsed.blocked.isEmpty && parsed.allowed.isEmpty) {
            return failed(request, subscription, SubscriptionFailure.EMPTY_LIST)
        }
        if (!contentStore.write(subscription.id, fetched.text)) {
            return failed(request, subscription, SubscriptionFailure.STORE_WRITE_FAILED)
        }

        val updated = subscription.copy(
            state = SubscriptionState.READY,
            ruleCount = parsed.blocked.size + parsed.allowed.size,
            lastSuccessAtMillis = clock(),
            lastError = null
        )
        record(subscription.id, updated, fetched.etag)
        return SubscriptionUpdateResult.Updated(updated, fetched.etag)
    }

    /**
     * Records a failure without touching the stored content.
     *
     * The rule count and the last success time describe the copy that is still on disk and still in
     * use, so they are kept: only the outcome of this attempt changes.
     */
    private fun failed(
        request: SubscriptionUpdateRequest,
        subscription: FilterSubscription,
        reason: SubscriptionFailure
    ): SubscriptionUpdateResult {
        val failed = subscription.copy(
            state = SubscriptionState.FAILED,
            lastError = reason.label
        )
        record(subscription.id, failed, request.etag)
        return SubscriptionUpdateResult.Failed(reason, failed)
    }

    private fun record(subscriptionId: String, subscription: FilterSubscription, etag: String?) {
        try {
            stateWriter.onState(subscriptionId, subscription, etag)
        } catch (_: Exception) {
            // Persisting the new bookkeeping is the caller's business; a failure there must not turn a
            // completed update into an error.
        }
    }
}
