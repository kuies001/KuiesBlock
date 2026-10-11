package tw.kuies.block.vpn

/** What a Private DNS setting change means for an already-running tunnel. */
enum class PrivateDnsChange {
    /** Nothing to do — protection is not running, or the notification changed nothing usable. */
    NONE,

    /**
     * The declaration itself is unchanged, so the tunnel must NOT be re-established. The wording can
     * still differ (for example strict hostname A -> B), and the UI refreshes that on its own.
     */
    REFRESH_STATUS,

    /** The declaration changed (strict <-> not strict), so the tunnel has to be re-established. */
    RESTART_TUNNEL
}

/**
 * Pure decision for reacting to Android's Private DNS setting while protection is running.
 *
 * It compares the declaration that is actually installed with the one the new setting needs, so a
 * repeated, coalesced or stale notification can never cause a rebuild: identical declarations never
 * restart, and a missing declaration means "not running, ignore".
 */
object PrivateDnsCoexistencePolicy {

    fun classify(applied: TunnelDeclaration?, candidate: TunnelDeclaration?): PrivateDnsChange = when {
        // Nothing installed yet: starting, stopping, or already stopped. A late callback is ignored
        // rather than obeyed, which is what keeps a stop from being undone by a stale notification.
        applied == null -> PrivateDnsChange.NONE
        // The new setting cannot be applied safely (for example E1 without a resolved address):
        // keep the working tunnel instead of guessing.
        candidate == null -> PrivateDnsChange.NONE
        applied == candidate -> PrivateDnsChange.REFRESH_STATUS
        else -> PrivateDnsChange.RESTART_TUNNEL
    }
}
