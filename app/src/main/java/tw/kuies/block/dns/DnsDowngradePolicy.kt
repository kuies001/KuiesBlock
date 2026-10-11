package tw.kuies.block.dns

/**
 * Guards against silently downgrading an encrypted DNS transport to cleartext.
 *
 * If DoH or DoT is selected, the app must never quietly fall back to plain DNS; a fallback is
 * only acceptable as an explicit, user-visible decision.
 */
object DnsDowngradePolicy {

    /** True when moving from [configured] to [target] would silently drop encryption. */
    fun isSilentDowngrade(configured: DnsProtocol, target: DnsProtocol): Boolean =
        configured.encrypted && !target.encrypted
}
