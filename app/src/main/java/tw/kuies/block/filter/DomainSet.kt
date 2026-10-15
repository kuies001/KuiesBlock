package tw.kuies.block.filter

/**
 * An immutable, sorted set of canonical domain names.
 *
 * Sorted rather than hashed so a lookup costs O(log n) string comparisons with no per-entry object
 * overhead and no rehashing while a 200k-rule list is loaded. The engine is consulted for every DNS
 * query, so the sorted array is also what keeps the cost predictable as the rule count grows.
 */
class DomainSet private constructor(private val domains: Array<String>) : Iterable<String> {

    val size: Int get() = domains.size

    val isEmpty: Boolean get() = domains.isEmpty()

    fun contains(domain: String): Boolean = domains.binarySearch(domain) >= 0

    /** True when any candidate is present; short-circuits on the first hit. */
    fun containsAny(candidates: List<String>): Boolean = candidates.any { contains(it) }

    /** Iteration order is the canonical sorted order. Never used on the packet path. */
    override fun iterator(): Iterator<String> = domains.iterator()

    companion object {
        val EMPTY = DomainSet(emptyArray())

        /** Canonicalises, sorts and de-duplicates [domains], dropping anything that is not a domain. */
        fun of(domains: Collection<String>): DomainSet {
            if (domains.isEmpty()) return EMPTY
            val collected = ArrayList<String>(domains.size)
            for (domain in domains) {
                DomainName.normalise(domain)?.let { collected.add(it) }
            }
            return ofNormalised(collected)
        }

        /** Same as [of] but for callers that already hold canonical names. */
        fun ofNormalised(domains: Collection<String>): DomainSet {
            if (domains.isEmpty()) return EMPTY
            val sorted = domains.toTypedArray()
            sorted.sort()
            var write = 0
            for (read in sorted.indices) {
                if (read == 0 || sorted[read] != sorted[read - 1]) {
                    sorted[write++] = sorted[read]
                }
            }
            return DomainSet(
                if (write == sorted.size) sorted else java.util.Arrays.copyOf(sorted, write)
            )
        }
    }
}
