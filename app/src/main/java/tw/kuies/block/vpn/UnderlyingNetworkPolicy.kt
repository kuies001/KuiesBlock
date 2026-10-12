package tw.kuies.block.vpn

import java.net.InetSocketAddress

/**
 * The transports the choice between candidate networks cares about. Android prefers a validated
 * local network (Ethernet, Wi-Fi) over a metered cellular one when both can reach the internet, and
 * [UnderlyingNetworkPolicy] mirrors that ordering as far as an app can observe it.
 */
enum class UnderlyingTransport { LOCAL, OTHER, CELLULAR }

/**
 * A non-VPN network that could carry the tunnel's upstream traffic, reduced to what the choice
 * depends on. Callers must only build one of these from a network that has `INTERNET` and is not a
 * VPN — our own tunnel must never become its own upstream.
 */
data class UnderlyingCandidate(
    /** The platform's stable handle for the network: identity, and a deterministic tie-break. */
    val handle: Long,
    val transport: UnderlyingTransport,
    val validated: Boolean,
    val metered: Boolean
)

/** Why the policy chose a network. Safe to log: no netId, no address, no name. */
enum class UnderlyingNetworkReason {
    /** Nothing to choose from: the caller keeps whatever it declared before. */
    NO_USABLE_NETWORK,

    /** The system named this network itself (its active network), so its answer was used as is. */
    SYSTEM_DEFAULT,

    /** Nothing was declared yet, so the best network on offer was chosen. */
    FIRST_CHOICE,

    /** A network that outranks the one in use appeared (typically Wi-Fi coming back). */
    BETTER_CHOICE_APPEARED,

    /** The network in use is gone, so the best remaining one takes over. */
    CURRENT_NO_LONGER_USABLE,

    /** An event arrived but the network in use still ranks highest: nothing to do. */
    CURRENT_STILL_RANKS_HIGHEST
}

data class UnderlyingNetworkDecision(
    val candidate: UnderlyingCandidate?,
    val reason: UnderlyingNetworkReason
)

/**
 * Which non-VPN network the tunnel should ride on.
 *
 * This exists because "the network that reported last" is not a choice: cellular reports link
 * properties all the time, so a steady stream of cellular events used to take the tunnel off a
 * validated Wi-Fi network that Android was still using, and nothing ever moved it back. The choice
 * is therefore made from the whole set of known networks, in a fixed order, and it is stable: as
 * long as the network in use still ranks highest it is never replaced, so repeated or duplicate
 * callbacks cannot churn the tunnel.
 *
 * Order of preference, highest first:
 *  1. the system's own answer, when it is one of the candidates (see [decide]);
 *  2. a validated network over an unvalidated one — an unvalidated network is not the one Android
 *     routes traffic over, which is also how the "yield to a bad Wi-Fi" case behaves;
 *  3. a local transport (Ethernet, Wi-Fi, Bluetooth, USB) over any other over cellular;
 *  4. unmetered over metered.
 *
 * Ties keep the network already in use, and otherwise fall back to the lowest handle, so the same
 * set of networks always produces the same answer.
 */
object UnderlyingNetworkPolicy {

    fun decide(
        candidates: List<UnderlyingCandidate>,
        current: UnderlyingCandidate?,
        systemDefault: UnderlyingCandidate? = null
    ): UnderlyingNetworkDecision {
        if (candidates.isEmpty()) {
            return UnderlyingNetworkDecision(null, UnderlyingNetworkReason.NO_USABLE_NETWORK)
        }
        // The system's answer wins whenever it is a network we can actually use: it is the network
        // Android itself routes traffic over, so following it cannot drift from the truth.
        val systemChoice = systemDefault?.takeIf { preferred ->
            candidates.any { it.handle == preferred.handle }
        }
        if (systemChoice != null) {
            return if (current?.handle == systemChoice.handle) {
                UnderlyingNetworkDecision(current, UnderlyingNetworkReason.CURRENT_STILL_RANKS_HIGHEST)
            } else {
                UnderlyingNetworkDecision(systemChoice, UnderlyingNetworkReason.SYSTEM_DEFAULT)
            }
        }

        val best = bestOf(candidates, keep = current)
        if (current != null && current.handle == best.handle) {
            return UnderlyingNetworkDecision(current, UnderlyingNetworkReason.CURRENT_STILL_RANKS_HIGHEST)
        }
        val reason = when {
            current == null -> UnderlyingNetworkReason.FIRST_CHOICE
            candidates.none { it.handle == current.handle } -> UnderlyingNetworkReason.CURRENT_NO_LONGER_USABLE
            else -> UnderlyingNetworkReason.BETTER_CHOICE_APPEARED
        }
        return UnderlyingNetworkDecision(best, reason)
    }

    /**
     * Whether the tunnel has to touch its upstream declaration at all: a different network, or the
     * same network reached through a different set of bootstrap DNS servers. An event that changes
     * neither is a no-op, which is what keeps a burst of callbacks from rebuilding the DoH
     * transport (and, during startup, from restarting the verification budget).
     */
    fun upstreamChangeRequired(
        appliedNetworkHandle: Long?,
        appliedDnsServers: List<InetSocketAddress>,
        chosenNetworkHandle: Long,
        chosenDnsServers: List<InetSocketAddress>
    ): Boolean = appliedNetworkHandle != chosenNetworkHandle || appliedDnsServers != chosenDnsServers

    /**
     * True while an event stamped with [eventGeneration] may still act on the tunnel. A network
     * event that was emitted for an earlier tunnel generation, or that arrives after protection was
     * stopped, is discarded instead of overwriting newer state — or resurrecting a stopped tunnel.
     */
    fun isCurrentEvent(running: Boolean, eventGeneration: Int, tunnelGeneration: Int): Boolean =
        running && eventGeneration == tunnelGeneration

    /** Debug-only label for a candidate: transport, validation and metering. No netId, no address. */
    fun describe(candidate: UnderlyingCandidate?): String = candidate?.let {
        buildString {
            append(it.transport.name.lowercase())
            if (it.validated) append("+validated")
            if (!it.metered) append("+unmetered")
        }
    } ?: "none"

    private fun bestOf(candidates: List<UnderlyingCandidate>, keep: UnderlyingCandidate?): UnderlyingCandidate {
        val bestRank = candidates.maxOf { rank(it) }
        val top = candidates.filter { rank(it) == bestRank }
        return top.firstOrNull { it.handle == keep?.handle } ?: top.minBy { it.handle }
    }

    private fun rank(candidate: UnderlyingCandidate): Int =
        (if (candidate.validated) VALIDATED else 0) +
            when (candidate.transport) {
                UnderlyingTransport.LOCAL -> LOCAL_TRANSPORT
                UnderlyingTransport.OTHER -> OTHER_TRANSPORT
                UnderlyingTransport.CELLULAR -> 0
            } +
            (if (candidate.metered) 0 else UNMETERED)

    /** Bigger than transport and metering together, so validation always wins first. */
    private const val VALIDATED = 8
    private const val LOCAL_TRANSPORT = 4
    private const val OTHER_TRANSPORT = 2
    private const val UNMETERED = 1
}
