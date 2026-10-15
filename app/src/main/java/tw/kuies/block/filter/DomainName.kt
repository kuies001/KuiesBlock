package tw.kuies.block.filter

import java.net.IDN

/**
 * Domain-name normalisation and validation for the filter engine.
 *
 * Rules and queries must be compared in exactly one canonical form, otherwise `EXAMPLE.com.` and
 * `example.com` would be treated as different rules and a block could be bypassed by a trailing dot.
 * The canonical form is: ASCII (IDN encoded), lower case, no trailing dot.
 *
 * Pure and free of Android dependencies so it is cheap to unit test.
 */
object DomainName {

    /** Longest legal DNS name, matching the 255-octet wire limit minus the root label. */
    const val MAX_LENGTH = 253

    /** Longest legal single label. */
    const val MAX_LABEL_LENGTH = 63

    /**
     * Returns the canonical form of [raw], or null when it cannot be a legal domain name.
     *
     * Accepts an already ASCII name without touching IDN (the common case, and the only shape the
     * built-in lists contain), and only pays for [IDN.toASCII] when a non-ASCII character is present.
     */
    fun normalise(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null

        // Fold case before IDN so the canonical form does not depend on the platform's IDNA case
        // handling; an upper-case non-ASCII label must hash to the same rule as its lower-case form.
        val lowered = trimmed.lowercase()
        val ascii = if (lowered.all { it.code < 0x80 }) lowered else toAscii(lowered) ?: return null
        val withoutTrailingDot = ascii.trimEnd('.')

        if (withoutTrailingDot.isEmpty() || withoutTrailingDot.length > MAX_LENGTH) return null
        if (!withoutTrailingDot.all { isAllowedCharacter(it) }) return null

        for (label in withoutTrailingDot.split('.')) {
            if (!isLegalLabel(label)) return null
        }
        return withoutTrailingDot
    }

    /**
     * True when [domain] is [suffix] itself or a subdomain of it.
     *
     * The label boundary is what keeps `notexample.com` from matching `example.com`: the character
     * before the suffix must be a dot, not just any character.
     */
    fun isSubdomainOrSelf(domain: String, suffix: String): Boolean {
        if (domain.length < suffix.length) return false
        if (!domain.endsWith(suffix)) return false
        if (domain.length == suffix.length) return true
        return domain[domain.length - suffix.length - 1] == '.'
    }

    /**
     * Every name that must be tried when matching [domain] against subdomain-scoped rules, shortest
     * suffix last: `a.b.example.com` yields `a.b.example.com`, `b.example.com`, `example.com`,
     * `com`. A rule can only match one of these, so the caller never walks the whole rule set.
     */
    fun suffixChain(domain: String): List<String> {
        val chain = ArrayList<String>(6)
        var start = 0
        while (start < domain.length) {
            chain.add(domain.substring(start))
            val nextDot = domain.indexOf('.', start)
            if (nextDot < 0) break
            start = nextDot + 1
        }
        return chain
    }

    private fun toAscii(name: String): String? = try {
        IDN.toASCII(name, IDN.ALLOW_UNASSIGNED)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isAllowedCharacter(c: Char): Boolean =
        c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'

    private fun isLegalLabel(label: String): Boolean {
        if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) return false
        if (label.startsWith('-') || label.endsWith('-')) return false
        return true
    }
}
