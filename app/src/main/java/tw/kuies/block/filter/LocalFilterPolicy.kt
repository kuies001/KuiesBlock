package tw.kuies.block.filter

import tw.kuies.block.dns.PrivateDnsMode

/** Whether the local rules can actually take effect right now, and why not when they cannot. */
enum class LocalFilterState {
    /** Android hands DNS to the tunnel and usable rules are stored. */
    ACTIVE,

    /** Strict Private DNS: Android resolves on its own, so no query ever reaches the rules. */
    PAUSED_PRIVATE_DNS,

    /** Rules are configured but nothing usable is stored yet — the first update has not succeeded. */
    NO_RULES
}

/**
 * Derives the state the UI must show, from the two things that decide it.
 *
 * Strict Private DNS is checked first on purpose: in that mode the system bypasses the tunnel
 * entirely, so it would be wrong to promise filtering just because rules exist. "Automatic" and
 * "off" both leave the tunnel as the resolver, which is why only the strict hostname mode pauses the
 * filter — the same rule [tw.kuies.block.vpn.DnsExperiment] uses to decide the DNS declaration.
 *
 * A vendor Android build could still route around the tunnel in some other way; the device
 * measurements for this project live in the repository's scripts documentation, and the UI copy says
 * the state rather than guaranteeing interception.
 */
object LocalFilterPolicy {

    fun state(privateDnsMode: PrivateDnsMode, usableRuleCount: Int): LocalFilterState = when {
        privateDnsMode == PrivateDnsMode.HOSTNAME -> LocalFilterState.PAUSED_PRIVATE_DNS
        usableRuleCount <= 0 -> LocalFilterState.NO_RULES
        else -> LocalFilterState.ACTIVE
    }
}
