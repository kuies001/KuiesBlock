package tw.kuies.block.filter

import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the snapshot the VPN consults and swaps it atomically.
 *
 * The service reads the snapshot on every DNS query while subscription updates finish on a
 * background thread. Because a snapshot is immutable and only ever replaced as a whole, a reader
 * either sees the previous rule set or the new one — never a half-loaded list — and no lock is taken
 * on the packet path.
 */
class FilterRepository {

    private val published = AtomicLong(0L)

    @Volatile
    private var current: FilterSnapshot = FilterSnapshot.EMPTY

    /** The snapshot to consult right now. Never null, never partially built. */
    fun snapshot(): FilterSnapshot = current

    val version: Long get() = current.version

    /**
     * Builds and publishes a snapshot, giving it the next version number so consumers can tell that
     * the rules changed even when the rule sets happen to be equal.
     */
    fun publish(
        manualAllow: Collection<ManualRule> = emptyList(),
        manualBlock: Collection<ManualRule> = emptyList(),
        subscriptionBlock: DomainSet = DomainSet.EMPTY,
        subscriptionAllow: DomainSet = DomainSet.EMPTY
    ): FilterSnapshot {
        val next = FilterSnapshot.build(
            manualAllow = manualAllow,
            manualBlock = manualBlock,
            subscriptionBlock = subscriptionBlock,
            subscriptionAllow = subscriptionAllow,
            version = published.incrementAndGet()
        )
        current = next
        return next
    }

    /** Publishes an empty rule set (no source ready yet) while still advancing the version. */
    fun clear(): FilterSnapshot {
        val next = FilterSnapshot.build(version = published.incrementAndGet())
        current = next
        return next
    }
}
