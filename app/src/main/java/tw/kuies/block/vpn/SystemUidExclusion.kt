package tw.kuies.block.vpn

/**
 * The packages whose UID always stays outside the tunnel.
 *
 * Android answers "which network am I on" from the calling process's default network, and the
 * system settings UI is what renders both the wireless-debugging address and the Private DNS
 * status (`AdbIpAddressPreferenceController` reads `getLinkProperties(getActiveNetwork())`).
 * Routing the system UID through the tunnel therefore makes those pages describe the tunnel
 * instead of the connection the user is actually on: wireless debugging shows the virtual address
 * and Private DNS reports a network that cannot complete strict DoT.
 *
 * The platform package shares UID 1000 with Settings, so excluding it keeps the system's own view
 * of the network truthful. Ordinary apps stay covered by the VPN.
 */
object SystemUidExclusion {

    /** The platform package; its UID (1000) is shared by Settings and the system server. */
    const val PLATFORM_PACKAGE = "android"

    val PACKAGES: List<String> = listOf(PLATFORM_PACKAGE)

    /**
     * Everything the tunnel must exclude: the system packages first, then the user's choices,
     * with duplicates and this app itself removed.
     */
    fun forTunnel(userExclusions: Collection<String>, selfPackage: String): List<String> =
        (PACKAGES + userExclusions).distinct().filter { it != selfPackage }

    fun isSystem(packageName: String): Boolean = packageName in PACKAGES
}
