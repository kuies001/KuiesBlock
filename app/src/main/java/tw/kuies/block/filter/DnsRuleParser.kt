package tw.kuies.block.filter

/** Why a non-empty, non-comment line could not become a rule. */
enum class RuleRejection {
    /** An Adblock modifier (`$third-party`, `$important`, …) we deliberately do not implement. */
    MODIFIER,

    /** A regular expression rule (`/ads/`), or any rule carrying a path such as a URL. */
    REGEX,

    /** A wildcard rule (`*`). */
    WILDCARD,

    /** A cosmetic/CSS rule (`##`, `#@#`, `#?#`). */
    CSS,

    /** Not a legal domain name. */
    INVALID_DOMAIN,

    /** Longer than the line limit. */
    TOO_LONG
}

/** What one line of a list means. */
sealed interface RuleLine {
    data object Blank : RuleLine
    data object Comment : RuleLine
    data class Blocked(val domain: String) : RuleLine
    data class Allowed(val domain: String) : RuleLine
    data class Rejected(val reason: RuleRejection) : RuleLine
}

/** Counts for a parsed list, so the UI can say what the list actually contributed. */
data class ParseStats(
    val lines: Int = 0,
    val blanks: Int = 0,
    val comments: Int = 0,
    val blocked: Int = 0,
    val allowed: Int = 0,
    val duplicates: Int = 0,
    val rejections: Map<RuleRejection, Int> = emptyMap(),
    val truncated: Boolean = false
) {
    val rejected: Int get() = rejections.values.sum()

    fun rejectionCount(reason: RuleRejection): Int = rejections[reason] ?: 0
}

/** A parsed list: the two rule sets plus the statistics describing the parse. */
data class ParsedRules(
    val blocked: DomainSet,
    val allowed: DomainSet,
    val stats: ParseStats
) {
    companion object {
        val EMPTY = ParsedRules(DomainSet.EMPTY, DomainSet.EMPTY, ParseStats())
    }
}

/**
 * Turns a block-list text file into rule sets.
 *
 * The parser only accepts shapes it fully understands and **counts everything else as rejected**, so
 * an Adblock modifier, regex, wildcard or CSS rule can never be mistaken for a plain domain and
 * silently block the wrong host. Nothing here interprets a rule as code.
 *
 * Pure and streaming: a 5 MB list is processed line by line, and [limits] stop a hostile or corrupt
 * file from exhausting memory.
 */
object DnsRuleParser {

    /** Longest accepted line; anything longer is rejected rather than truncated. */
    const val MAX_LINE_LENGTH = 4096

    /** Most rules accepted from one list. */
    const val MAX_RULES = 500_000

    /** The host prefixes a hosts-format block line may use. */
    private val HOSTS_BLOCK_IPS = setOf("0.0.0.0", "127.0.0.1", "::1")

    /**
     * Parses [text].
     *
     * [maxRules] exists for tests and for callers that want a tighter budget than [MAX_RULES].
     */
    fun parse(text: CharSequence, maxRules: Int = MAX_RULES): ParsedRules {
        val blocked = ArrayList<String>(1024)
        val allowed = ArrayList<String>(64)
        var lines = 0
        var blanks = 0
        var comments = 0
        var rejectedCounts = LinkedHashMap<RuleRejection, Int>()
        var truncated = false

        for (line in text.lineSequence()) {
            lines++
            if (blocked.size + allowed.size >= maxRules) {
                truncated = true
                break
            }
            when (val outcome = classifyLine(line)) {
                RuleLine.Blank -> blanks++
                RuleLine.Comment -> comments++
                is RuleLine.Blocked -> blocked.add(outcome.domain)
                is RuleLine.Allowed -> allowed.add(outcome.domain)
                is RuleLine.Rejected ->
                    rejectedCounts[outcome.reason] = (rejectedCounts[outcome.reason] ?: 0) + 1
            }
        }

        val blockedSet = DomainSet.ofNormalised(blocked)
        val allowedSet = DomainSet.ofNormalised(allowed)
        val accepted = blocked.size + allowed.size
        val duplicates = (blocked.size - blockedSet.size) + (allowed.size - allowedSet.size)

        return ParsedRules(
            blocked = blockedSet,
            allowed = allowedSet,
            stats = ParseStats(
                lines = lines,
                blanks = blanks,
                comments = comments,
                blocked = blockedSet.size,
                allowed = allowedSet.size,
                duplicates = duplicates,
                rejections = rejectedCounts,
                truncated = truncated || accepted > maxRules
            )
        )
    }

    /**
     * Classifies one line. Malformed input can never throw: an unexpected failure is reported as
     * [RuleRejection.INVALID_DOMAIN] so one bad line cannot abort a whole list.
     */
    fun classifyLine(line: String): RuleLine {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return RuleLine.Blank
        if (trimmed.startsWith("!") || trimmed.startsWith("[")) return RuleLine.Comment
        if (trimmed.length > MAX_LINE_LENGTH) return RuleLine.Rejected(RuleRejection.TOO_LONG)

        // Syntax we deliberately do not implement. Checked before anything else so a rule carrying it
        // is reported as unsupported instead of being parsed as a domain.
        if (trimmed.contains('$')) return RuleLine.Rejected(RuleRejection.MODIFIER)
        if (trimmed.contains('*')) return RuleLine.Rejected(RuleRejection.WILDCARD)
        if (trimmed.contains('/')) return RuleLine.Rejected(RuleRejection.REGEX)
        if (trimmed.contains('#')) return RuleLine.Rejected(RuleRejection.CSS)

        val allowed = trimmed.startsWith("@@")
        val body = if (allowed) trimmed.substring(2).trim() else trimmed
        if (body.isEmpty()) return RuleLine.Rejected(RuleRejection.INVALID_DOMAIN)

        return try {
            val domain = extractDomain(body) ?: return RuleLine.Rejected(RuleRejection.INVALID_DOMAIN)
            val normalised = DomainName.normalise(domain)
                ?: return RuleLine.Rejected(
                    if (domain.length > DomainName.MAX_LENGTH) RuleRejection.TOO_LONG
                    else RuleRejection.INVALID_DOMAIN
                )
            if (allowed) RuleLine.Allowed(normalised) else RuleLine.Blocked(normalised)
        } catch (_: RuntimeException) {
            RuleLine.Rejected(RuleRejection.INVALID_DOMAIN)
        }
    }

    /**
     * Extracts the domain from an accepted body shape:
     * `||domain^`, `||domain`, `domain`, or a hosts line `0.0.0.0 domain` / `127.0.0.1 domain`.
     *
     * Returns null for anything else, including a leftover anchor or pipe character.
     */
    private fun extractDomain(body: String): String? {
        if (body.startsWith("||")) {
            val rest = body.substring(2)
            if (rest.contains('|')) return null
            val domain = rest.removeSuffix("^")
            if (domain.contains('^')) return null
            return domain.ifEmpty { null }
        }

        val parts = body.split(' ', '\t').filter { it.isNotEmpty() }
        return when {
            parts.size == 1 -> parts[0].ifEmpty { null }
            parts.size == 2 && parts[0] in HOSTS_BLOCK_IPS -> parts[1].ifEmpty { null }
            else -> null
        }
    }
}
