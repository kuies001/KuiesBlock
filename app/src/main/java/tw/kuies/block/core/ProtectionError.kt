package tw.kuies.block.core

/**
 * Non-sensitive reason protection could not start, or had to stop.
 *
 * Deliberately coarse: it is safe to show to the user and to write to Logcat, and it can never
 * contain a domain, an address or any query content.
 */
enum class ProtectionError {
    /** The system refused to create the TUN interface. */
    TUN_ESTABLISH_FAILED,
    /** No usable non-VPN network was available to send upstream queries through. */
    NO_UNDERLYING_NETWORK,
    /** The configured transport is not DoH. */
    ENDPOINT_NOT_DOH,
    /** The DoH host could not be reached at all. */
    DNS_UNREACHABLE,
    /** The DoH request timed out. */
    DNS_TIMEOUT,
    /** TLS could not be validated for the DoH endpoint. */
    TLS_FAILURE,
    /** The upstream answered with something that was not a valid DoH/DNS response. */
    DNS_INVALID_RESPONSE
}
