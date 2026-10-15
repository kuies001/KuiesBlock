package tw.kuies.block.filter

/** How far a rule reaches: one exact host, or that host and everything below it. */
enum class MatchScope {
    /** Only the host itself: `a.example.com` does not match a rule for `example.com`. */
    EXACT,

    /** The host and all of its subdomains. This is what every list-file format means. */
    SUBDOMAIN
}

/** The outcome of consulting the local rules for one query name. */
enum class FilterDecision {
    /** A local rule deliberately allowed it; the query still goes upstream. */
    ALLOW,

    /** A local rule blocked it; answer locally without contacting upstream. */
    BLOCK,

    /** No local rule matched; behaviour is exactly what it was before this feature existed. */
    UPSTREAM
}

/** A host the user added by hand, with the scope they chose for it. */
data class ManualRule(val domain: String, val scope: MatchScope = MatchScope.SUBDOMAIN)

/**
 * The complete rule set the engine consults, in one immutable value.
 *
 * Immutability is what makes the packet path safe: an update publishes a whole new snapshot and
 * readers keep using the one they already hold, so no lock, no partially-updated state and no query
 * ever sees a half-loaded list.
 *
 * Priority order (first hit wins) is fixed by the four sets below and documented in the project's
 * v0.4.0 design:
 *  1. user allow  2. user block  3. subscription exceptions  4. subscription blocks  → else upstream
 */
class FilterSnapshot private constructor(
    private val userAllowExact: DomainSet,
    private val userAllowSubdomain: DomainSet,
    private val userBlockExact: DomainSet,
    private val userBlockSubdomain: DomainSet,
    private val subscriptionAllow: DomainSet,
    private val subscriptionBlock: DomainSet,
    val version: Long,
    val subscriptionRuleCount: Int
) {

    /** True when no source contributes any rule yet, i.e. nothing can be decided locally. */
    val isEmpty: Boolean
        get() = subscriptionBlock.isEmpty && subscriptionAllow.isEmpty &&
            userAllowExact.isEmpty && userAllowSubdomain.isEmpty &&
            userBlockExact.isEmpty && userBlockSubdomain.isEmpty

    val userRuleCount: Int
        get() = userAllowExact.size + userAllowSubdomain.size + userBlockExact.size + userBlockSubdomain.size

    /**
     * Decides [domain] against the rules, in priority order.
     *
     * [domain] must already be canonical (see [DomainName.normalise]); an empty name is never blocked.
     */
    fun decide(domain: String): FilterDecision {
        if (domain.isEmpty()) return FilterDecision.UPSTREAM
        val chain = DomainName.suffixChain(domain)

        if (userAllowExact.contains(domain) || matchesSubdomain(userAllowSubdomain, chain)) {
            return FilterDecision.ALLOW
        }
        if (userBlockExact.contains(domain) || matchesSubdomain(userBlockSubdomain, chain)) {
            return FilterDecision.BLOCK
        }
        if (matchesSubdomain(subscriptionAllow, chain)) return FilterDecision.ALLOW
        if (matchesSubdomain(subscriptionBlock, chain)) return FilterDecision.BLOCK
        return FilterDecision.UPSTREAM
    }

    /** Convenience for callers holding a raw name; returns UPSTREAM for anything unparseable. */
    fun decideRaw(domain: String): FilterDecision =
        DomainName.normalise(domain)?.let { decide(it) } ?: FilterDecision.UPSTREAM

    private fun matchesSubdomain(set: DomainSet, chain: List<String>): Boolean =
        !set.isEmpty && set.containsAny(chain)

    companion object {
        val EMPTY = FilterSnapshot(
            userAllowExact = DomainSet.EMPTY,
            userAllowSubdomain = DomainSet.EMPTY,
            userBlockExact = DomainSet.EMPTY,
            userBlockSubdomain = DomainSet.EMPTY,
            subscriptionAllow = DomainSet.EMPTY,
            subscriptionBlock = DomainSet.EMPTY,
            version = 0L,
            subscriptionRuleCount = 0
        )

        /**
         * Builds a snapshot from raw inputs.
         *
         * Manual entries are canonicalised here, so an invalid domain typed by the user (or a stale
         * stored value) is dropped instead of becoming a rule that can never match. Subscriptions
         * arrive as ready-made [DomainSet]s because the parser already produced canonical, sorted
         * sets — re-normalising a 200k-rule list on every update would be wasted work.
         */
        fun build(
            manualAllow: Collection<ManualRule> = emptyList(),
            manualBlock: Collection<ManualRule> = emptyList(),
            subscriptionBlock: DomainSet = DomainSet.EMPTY,
            subscriptionAllow: DomainSet = DomainSet.EMPTY,
            version: Long = 0L
        ): FilterSnapshot {
            val allowExact = ArrayList<String>()
            val allowSubdomain = ArrayList<String>()
            splitManual(manualAllow, allowExact, allowSubdomain)
            val blockExact = ArrayList<String>()
            val blockSubdomain = ArrayList<String>()
            splitManual(manualBlock, blockExact, blockSubdomain)

            return FilterSnapshot(
                userAllowExact = DomainSet.ofNormalised(allowExact),
                userAllowSubdomain = DomainSet.ofNormalised(allowSubdomain),
                userBlockExact = DomainSet.ofNormalised(blockExact),
                userBlockSubdomain = DomainSet.ofNormalised(blockSubdomain),
                subscriptionAllow = subscriptionAllow,
                subscriptionBlock = subscriptionBlock,
                version = version,
                subscriptionRuleCount = subscriptionBlock.size + subscriptionAllow.size
            )
        }

        private fun splitManual(
            rules: Collection<ManualRule>,
            exact: MutableCollection<String>,
            subdomain: MutableCollection<String>
        ) {
            for (rule in rules) {
                val domain = DomainName.normalise(rule.domain) ?: continue
                if (rule.scope == MatchScope.EXACT) exact.add(domain) else subdomain.add(domain)
            }
        }
    }
}
