package tw.kuies.block.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process

/**
 * What the platform could establish about another VPN owning the default network.
 *
 * Three outcomes, not two, because "the platform would not tell us" is a different fact from "there is
 * another VPN" and from "there is not" — and treating it as "there is not" is how a background start
 * could take a slot it was never able to prove was free.
 */
enum class OtherVpnState {

    /** Another app's VPN owns the active network. */
    PRESENT,

    /** No VPN transport owns the active network — established positively, not by absence of evidence. */
    ABSENT,

    /** The platform could not be read far enough to tell. Callers that act on this must do nothing. */
    UNKNOWN
}

/**
 * Whether a VPN other than KuiesBlock currently owns the system's default network.
 *
 * The guide only ever uses this to show a plain notice: it never stops, disables or reconfigures
 * anything, because another app's VPN is the user's own arrangement.
 *
 * The judgement is split from the platform read so it can be unit tested; [OtherVpnProbe] is the thin
 * part that talks to `ConnectivityManager`.
 */
object OtherVpnPolicy {

    /**
     * [defaultNetworkHasVpnTransport] is the system's default (active) network carrying the VPN
     * transport; [defaultNetworkOwnerUid] is that network's owner when the platform reports one.
     *
     * Our own tunnel is excluded twice over: by ownership where the platform tells us the owner, and
     * by [ourTunnelRunning] otherwise. The second test also covers the moment just after our tunnel
     * stops, when the platform can still report it as the active network for a frame.
     */
    fun state(
        defaultNetworkHasVpnTransport: Boolean,
        defaultNetworkOwnerUid: Int?,
        ourUid: Int,
        ourTunnelRunning: Boolean
    ): OtherVpnState {
        if (!defaultNetworkHasVpnTransport) return OtherVpnState.ABSENT
        if (defaultNetworkOwnerUid == ourUid) return OtherVpnState.ABSENT
        // A VPN transport without our tunnel running is somebody else's: whether the owner is named as
        // a different uid or not reported at all, the slot is not provably ours.
        return if (ourTunnelRunning) OtherVpnState.ABSENT else OtherVpnState.PRESENT
    }

    /** The plain notice the guide shows. Deliberately only the positive case. */
    fun anotherVpnHoldsTheSlot(
        defaultNetworkHasVpnTransport: Boolean,
        defaultNetworkOwnerUid: Int?,
        ourUid: Int,
        ourTunnelRunning: Boolean
    ): Boolean = state(
        defaultNetworkHasVpnTransport = defaultNetworkHasVpnTransport,
        defaultNetworkOwnerUid = defaultNetworkOwnerUid,
        ourUid = ourUid,
        ourTunnelRunning = ourTunnelRunning
    ) == OtherVpnState.PRESENT
}

/** Reads the platform state [OtherVpnPolicy] judges. Read-only: it never touches any VPN. */
object OtherVpnProbe {

    /**
     * What the platform says about another VPN on the default network.
     *
     * Every read that cannot be completed answers [OtherVpnState.UNKNOWN] rather than `false`: a null
     * `ConnectivityManager`, no active network and a missing capability set are all "we could not
     * look", and a boot-time start must not treat that as permission to take the slot. The cost is
     * real and deliberate — the best-effort boot path will skip when the device has no network yet,
     * which is common at `BOOT_COMPLETED`; the system's Always-on VPN does not depend on it.
     */
    fun state(context: Context, ourTunnelRunning: Boolean): OtherVpnState {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return OtherVpnState.UNKNOWN
        val active = manager.activeNetwork ?: return OtherVpnState.UNKNOWN
        val capabilities = manager.getNetworkCapabilities(active) ?: return OtherVpnState.UNKNOWN
        // Owner UID is only reported from Android 10 on; below that the "our own tunnel" question is
        // answered by `ourTunnelRunning` alone.
        val ownerUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.ownerUid.takeIf { it != Process.INVALID_UID }
        } else {
            null
        }
        return OtherVpnPolicy.state(
            defaultNetworkHasVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            defaultNetworkOwnerUid = ownerUid,
            ourUid = Process.myUid(),
            ourTunnelRunning = ourTunnelRunning
        )
    }

    /** The plain notice the guide shows; only a positively identified other VPN counts. */
    fun anotherVpnHoldsTheSlot(context: Context, ourTunnelRunning: Boolean): Boolean =
        state(context, ourTunnelRunning) == OtherVpnState.PRESENT
}
