package tw.kuies.block.core

/**
 * Pure transition rules for [ProtectionState].
 *
 * Kept free of any Android type so the whole lifecycle — including the fail-open path — can be
 * unit tested on the JVM.
 */
object ProtectionStateRules {

    /** Maps the result of the `VpnService.prepare()` consent dialog onto a state. */
    fun afterAuthorizationRequest(granted: Boolean): ProtectionState =
        if (granted) ProtectionState.AUTHORIZED_IDLE else ProtectionState.NOT_AUTHORIZED

    /** The tunnel is being created and the DNS path is not verified yet. */
    fun afterServiceStarting(): ProtectionState = ProtectionState.STARTING

    /** The TUN interface exists and a real DNS round trip succeeded. */
    fun afterDnsVerified(): ProtectionState = ProtectionState.ACTIVE

    /** The tunnel stopped because the user turned protection off. */
    fun afterServiceStopped(): ProtectionState = ProtectionState.AUTHORIZED_IDLE

    /** Protection had to stop because the DNS path failed; the normal network is restored. */
    fun afterFailure(): ProtectionState = ProtectionState.ERROR

    /** The system revoked the VPN authorization. */
    fun afterAuthorizationRevoked(): ProtectionState = ProtectionState.NOT_AUTHORIZED

    /**
     * Whether DNS traffic is currently being intercepted and forwarded.
     *
     * Only a verified tunnel counts, so the UI cannot show 「防護已啟用」 for an authorized or
     * half-started tunnel.
     */
    fun isInterceptionActive(state: ProtectionState): Boolean = state == ProtectionState.ACTIVE
}
