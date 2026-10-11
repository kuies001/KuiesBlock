package tw.kuies.block.core

/**
 * Which DNS path the device is actually using while protection is up.
 *
 * "The VPN is running" and "KuiesBlock is intercepting DNS" are two different facts: in strict
 * Private DNS mode the tunnel stays up but the system keeps the encrypted resolution path for
 * itself. The UI must be able to tell them apart instead of implying that an active tunnel means
 * intercepted queries.
 */
enum class DnsPath {
    /** Queries are captured by the KuiesBlock tunnel and answered over DoH. */
    INTERCEPTED,

    /**
     * Strict Private DNS keeps the encrypted path for itself; KuiesBlock keeps the tunnel up but
     * declares no DNS server, so it intercepts nothing and its query counter stays at 「無資料」.
     * Filtering still happens, because the system's DoT server is the same AdGuard Home.
     */
    SYSTEM_PRIVATE_DNS
}
