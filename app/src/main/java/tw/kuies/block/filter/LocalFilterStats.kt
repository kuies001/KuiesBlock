package tw.kuies.block.filter

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many queries the local rules have answered since the process started.
 *
 * The count exists so the effect of the local filter can be **proved** rather than inferred: a reply
 * that came from the rules is otherwise indistinguishable from an upstream NXDOMAIN, because the
 * upstream resolver also answers "does not exist" for the names it blocks itself.
 *
 * It is deliberately process-only and is not shown in the UI — this version makes no claim about
 * blocking statistics, and the earlier decision not to display interception counts still stands.
 */
object LocalFilterStats {

    private val blockedCount = AtomicInteger(0)

    val blocked: Int get() = blockedCount.get()

    fun recordBlock() {
        blockedCount.incrementAndGet()
    }

    /** Only for verification tooling; production code never resets the count. */
    fun reset() {
        blockedCount.set(0)
    }
}
