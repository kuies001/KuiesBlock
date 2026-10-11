package tw.kuies.block.core

/**
 * High-level state of KuiesBlock's local DNS protection.
 *
 * [ACTIVE] is the only state that means "protection is really working": the TUN interface was
 * created and a real DoH round trip has succeeded. Nothing else may be presented as enabled.
 */
enum class ProtectionState {
    /** The user has never granted the VPN authorization, or the system revoked it. */
    NOT_AUTHORIZED,

    /** VPN authorization is granted, but the tunnel is not running. */
    AUTHORIZED_IDLE,

    /** The tunnel is being created and the first DoH round trip is being verified. */
    STARTING,

    /** The tunnel is up and DNS queries are completing through DoH. */
    ACTIVE,

    /** Protection stopped because the DNS path failed; the user's normal network is restored. */
    ERROR
}
