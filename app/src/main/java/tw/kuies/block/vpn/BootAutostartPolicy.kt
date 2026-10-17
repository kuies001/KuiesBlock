package tw.kuies.block.vpn

/**
 * Whether a `BOOT_COMPLETED` broadcast is allowed to start protection.
 *
 * Pure, so every gate is unit tested and the receiver stays a shell with no decisions in it. It is
 * fail-closed: anything it cannot positively establish becomes a skip, because a wrong "start" costs
 * the user their VPN slot and a wrong "skip" only means protection waits for the app to be opened.
 */
object BootAutostartPolicy {

    enum class Decision {
        /** Start the tunnel once, with no consent dialog and no retry. */
        START,

        /** The user never asked for this. */
        SKIP_NOT_ENABLED,

        /** Their last word on the protection switch was "off"; a restart must not overrule it. */
        SKIP_USER_TURNED_IT_OFF,

        /** Always-on VPN is on, so the system owns the start — starting too would be a second tunnel. */
        SKIP_ALWAYS_ON,

        /** Protection is already coming up or running. */
        SKIP_ALREADY_RUNNING,

        /** No VPN authorization is held. Never satisfied by opening the consent dialog from the background. */
        SKIP_NOT_AUTHORIZED,

        /** Another VPN holds the single VPN slot; KuiesBlock never takes it away. */
        SKIP_OTHER_VPN,

        /**
         * The platform would not say whether another VPN holds the slot.
         *
         * A separate decision from [SKIP_OTHER_VPN] so a skip can be explained afterwards, but the same
         * outcome on purpose: "could not tell" is not "there is none", and starting on that basis could
         * take a slot this app was never able to prove was free.
         */
        SKIP_OTHER_VPN_UNKNOWN
    }

    /**
     * Whether a restart may start protection.
     *
     * [alwaysOnVpn] is deliberately the weakest input, and a caller must not assume it can be known at
     * all. `VpnService.isAlwaysOn()` is an **instance** method, so the boot receiver — which decides
     * before any VpnService exists — has to read `Settings.Secure.always_on_vpn_app` instead, and on the
     * SM-F7660 that read never reported this app across two reboots while the setting really did name it
     * (measured 2026-10-17). The gate stays because it is correct whenever the read works, but the
     * practical double-start guard is [serviceRunning]: the platform's own always-on start brings the
     * service up first, so this app then finds the tunnel already running and skips — which is what the
     * device showed, with no second tunnel, no retry and no crash.
     */
    fun decide(
        preferenceEnabled: Boolean,
        authorizationGranted: Boolean,
        userWantsProtection: Boolean,
        serviceRunning: Boolean,
        alwaysOnVpn: Boolean,
        otherVpn: OtherVpnState
    ): Decision = when {
        !preferenceEnabled -> Decision.SKIP_NOT_ENABLED
        !userWantsProtection -> Decision.SKIP_USER_TURNED_IT_OFF
        alwaysOnVpn -> Decision.SKIP_ALWAYS_ON
        serviceRunning -> Decision.SKIP_ALREADY_RUNNING
        !authorizationGranted -> Decision.SKIP_NOT_AUTHORIZED
        otherVpn == OtherVpnState.PRESENT -> Decision.SKIP_OTHER_VPN
        otherVpn == OtherVpnState.UNKNOWN -> Decision.SKIP_OTHER_VPN_UNKNOWN
        else -> Decision.START
    }
}
