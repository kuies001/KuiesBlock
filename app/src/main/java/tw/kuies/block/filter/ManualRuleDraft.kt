package tw.kuies.block.filter

/**
 * One line of user input, turned into a [ManualRule] — or into the reason it cannot become one.
 *
 * This is the only place that has to know what a hand-typed allow/block entry may look like. It is
 * deliberately strict: a string that is not a domain name is *rejected*, never trimmed into one, so
 * the user is told what is wrong instead of silently getting a rule that can never match anything
 * (the engine compares canonical names only — see [DomainName]).
 *
 * Pure and free of Android as well as Compose, so the wording decision ("is this a domain?") is
 * unit testable and the composable that calls it stays a pure function of its parameters.
 *
 * Normalisation is [DomainName.normalise] and nothing else. The matching itself lives in
 * [FilterSnapshot]; a draft only produces the rule.
 */
object ManualRuleDraft {

    /**
     * Longest domain a draft accepts, matching [DomainName.MAX_LENGTH] and the real DNS wire limit.
     *
     * A longer entry is refused here rather than passed on, so the user gets a plain "too long"
     * instead of the generic "not a domain" that [DomainName.normalise] would produce.
     */
    const val MAX_DOMAIN_LENGTH: Int = DomainName.MAX_LENGTH

    /** Why a line of input could not become a rule. Each value has one user-facing message. */
    enum class Rejection {
        /** Nothing (or only whitespace) was entered. */
        EMPTY,

        /** Longer than [MAX_DOMAIN_LENGTH] characters. */
        TOO_LONG,

        /** Not a legal domain name: bad characters, a bad label, or an unencodable IDN label. */
        NOT_A_DOMAIN,

        /** A literal IPv4 or IPv6 address. The local filter matches names, not addresses. */
        IP_ADDRESS,

        /** A URL, a path, a port, a query or whitespace — i.e. more than a bare domain. */
        URL_OR_PATH,

        /** The same domain is already in the target list with the same [MatchScope]. */
        DUPLICATE
    }

    /** The outcome of [parse]: either a rule ready to be added, or the reason it was refused. */
    sealed interface Result {
        data class Accepted(val rule: ManualRule) : Result
        data class Rejected(val reason: Rejection) : Result
    }

    /**
     * Parses one line of user input for a rule that would join [existing].
     *
     * Leading and trailing whitespace is trimmed, exactly as [DomainName.normalise] does; any
     * whitespace *inside* the entry is refused, because it cannot be part of a host name. [existing]
     * is only consulted for duplicate detection: the same domain with a different [scope] is a
     * different rule and is accepted, while the same domain with the same scope is
     * [Rejection.DUPLICATE].
     *
     * The returned [ManualRule.domain] is the canonical form, so storing it cannot later produce a
     * rule that the engine drops as invalid.
     */
    fun parse(raw: String, scope: MatchScope, existing: List<ManualRule>): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Rejected(Rejection.EMPTY)
        if (trimmed.length > MAX_DOMAIN_LENGTH) return Result.Rejected(Rejection.TOO_LONG)

        // Addresses are checked before URL syntax: an IPv6 literal contains ':' and would otherwise
        // be reported as a URL, which would tell the user the wrong thing about what they typed.
        if (looksLikeIpLiteral(trimmed)) return Result.Rejected(Rejection.IP_ADDRESS)
        if (containsUrlSyntax(trimmed)) return Result.Rejected(Rejection.URL_OR_PATH)

        val domain = DomainName.normalise(trimmed) ?: return Result.Rejected(Rejection.NOT_A_DOMAIN)
        // IDN encoding can lengthen a name, so the canonical form is checked too.
        if (domain.length > MAX_DOMAIN_LENGTH) return Result.Rejected(Rejection.TOO_LONG)

        val isDuplicate = existing.any { rule ->
            rule.scope == scope && DomainName.normalise(rule.domain) == domain
        }
        if (isDuplicate) return Result.Rejected(Rejection.DUPLICATE)

        return Result.Accepted(ManualRule(domain = domain, scope = scope))
    }

    /**
     * The rules whose domain contains [query], case-insensitively, in the given order.
     *
     * A blank query matches everything, which is what a search box must do before the user types
     * anything: an empty box must not read as an empty list. The query itself is trimmed so a stray
     * space from an autocomplete does not hide every rule.
     */
    fun search(rules: List<ManualRule>, query: String): List<ManualRule> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return rules
        return rules.filter { it.domain.lowercase().contains(needle) }
    }

    /**
     * True for a literal IPv4/IPv6 address.
     *
     * Deliberately a syntactic check, not a lookup: `InetAddress.getByName` would resolve a name
     * over the network on a code path that only validates what the user typed.
     */
    private fun looksLikeIpLiteral(raw: String): Boolean = looksLikeIpv4(raw) || looksLikeIpv6(raw)

    /** Exactly four dot-separated decimal groups, each at most 255. */
    private fun looksLikeIpv4(raw: String): Boolean {
        val groups = raw.split('.')
        if (groups.size != 4) return false
        return groups.all { group ->
            group.isNotEmpty() && group.length <= 3 && group.all { it in '0'..'9' } &&
                group.toInt() <= 255
        }
    }

    /**
     * A compressed or full IPv6 literal: only hexadecimal digits, dots (for an embedded IPv4 tail)
     * and at least two colons. One colon is enough to fail [containsUrlSyntax] anyway; requiring two
     * is what keeps `host:8080` from being called an address.
     */
    private fun looksLikeIpv6(raw: String): Boolean {
        if (raw.count { it == ':' } < 2) return false
        return raw.all { it.isHexDigit() || it == ':' || it == '.' }
    }

    /**
     * True when the entry carries more than a host name.
     *
     * Every URL scheme ends in a colon (`https:`, `mailto:`), so the colon rule covers "contains a
     * URL scheme" on its own; `/`, `?` and `#` cover paths, ports and fragments, and whitespace
     * covers a pasted sentence or a second line.
     */
    private fun containsUrlSyntax(raw: String): Boolean =
        raw.any { it == '/' || it == ':' || it == '?' || it == '#' || it.isWhitespace() }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
