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

/** Why the policy reached its answer. Safe to log: it names no hostname and no address. */
enum class PrivateDnsChangeReason {
    NO_TUNNEL,
    UNUSABLE_CANDIDATE,
    DECLARATION_UNCHANGED,
    DECLARATION_CHANGED
}

data class PrivateDnsChangeDecision(
    val change: PrivateDnsChange,
    val reason: PrivateDnsChangeReason
)

/**
 * A non-sensitive label for a tunnel declaration. It deliberately never contains an address, so a
 * diagnostic built from it is safe to paste into a report.
 */
enum class TunnelDeclarationKind { INTERCEPTING, NO_DNS_DECLARED, REAL_DOT_WITHOUT_ROUTE }

/** The label for this declaration, judged against the virtual DNS address KuiesBlock advertises. */
fun TunnelDeclaration.kind(virtualDnsAddress: String): TunnelDeclarationKind = when {
    dnsServer == virtualDnsAddress -> TunnelDeclarationKind.INTERCEPTING
    dnsServer == null -> TunnelDeclarationKind.NO_DNS_DECLARED
    else -> TunnelDeclarationKind.REAL_DOT_WITHOUT_ROUTE
}

/**
 * Pure decision for reacting to Android's Private DNS setting while protection is running.
 *
 * It compares the declaration that is actually installed with the one the new setting needs, so a
 * repeated, coalesced or stale notification can never cause a rebuild: identical declarations never
 * restart, and a missing declaration means "not running, ignore".
 */
object PrivateDnsCoexistencePolicy {

    fun decide(applied: TunnelDeclaration?, candidate: TunnelDeclaration?): PrivateDnsChangeDecision = when {
        // Nothing installed yet: starting, stopping, or already stopped. A late callback is ignored
        // rather than obeyed, which is what keeps a stop from being undone by a stale notification.
        applied == null ->
            PrivateDnsChangeDecision(PrivateDnsChange.NONE, PrivateDnsChangeReason.NO_TUNNEL)
        // The new setting cannot be applied safely (for example E1 without a resolved address):
        // keep the working tunnel instead of guessing.
        candidate == null ->
            PrivateDnsChangeDecision(PrivateDnsChange.NONE, PrivateDnsChangeReason.UNUSABLE_CANDIDATE)
        applied == candidate ->
            PrivateDnsChangeDecision(PrivateDnsChange.REFRESH_STATUS, PrivateDnsChangeReason.DECLARATION_UNCHANGED)
        else ->
            PrivateDnsChangeDecision(PrivateDnsChange.RESTART_TUNNEL, PrivateDnsChangeReason.DECLARATION_CHANGED)
    }

    fun classify(applied: TunnelDeclaration?, candidate: TunnelDeclaration?): PrivateDnsChange =
        decide(applied, candidate).change

    /**
     * Debug-only diagnostic: the private DNS mode, the declaration that is installed, the one the new
     * setting needs, the action and its reason, and the tunnel generation. It carries no hostname, no
     * address and no query content, so it is safe to share.
     */
    fun diagnostic(
        privateDnsMode: String,
        applied: TunnelDeclarationKind?,
        candidate: TunnelDeclarationKind?,
        decision: PrivateDnsChangeDecision,
        generation: Int
    ): String = "dnsDiag mode=$privateDnsMode applied=${applied ?: "none"} " +
        "candidate=${candidate ?: "none"} action=${decision.change} " +
        "reason=${decision.reason} generation=$generation"
}
