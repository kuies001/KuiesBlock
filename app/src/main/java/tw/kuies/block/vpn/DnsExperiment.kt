package tw.kuies.block.vpn

import android.content.Context
import android.content.pm.ApplicationInfo
import tw.kuies.block.dns.packet.Bytes

/**
 * Debug-only Android Private-DNS coexistence experiments (E1 / E2).
 *
 * These exist to answer one open question on a real device: with Private DNS in strict hostname
 * mode, can the DNS-only tunnel stay up and the phone still resolve names, without a TCP stack?
 *
 * Release safety: [DnsExperiment.effectiveMode] returns [DnsExperimentMode.BASELINE] whenever the
 * APK is not debuggable, so a release build can never run an experiment; and the only way to set a
 * mode — [DnsExperiment.setMode] — is called from a component that exists only in the debug source
 * set. [DnsExperimentMode.BASELINE] reproduces the shipped configuration exactly.
 */
enum class DnsExperimentMode(val storageKey: String) {
    /** Exactly today's shipped configuration: virtual DNS declared and routed. */
    BASELINE("baseline"),

    /** E2: keep the tunnel, keep the IPv4 address and the /32 route, but declare no DNS server. */
    E2_NO_DNS_DECLARATION("e2"),

    /** E1: declare the real DoT server address, and do not route it into the tunnel. */
    E1_REAL_DOT_ADDRESS("e1");

    companion object {
        val DEFAULT = BASELINE

        fun fromStorageKeyOrNull(key: String?): DnsExperimentMode? =
            entries.firstOrNull { it.storageKey.equals(key?.trim(), ignoreCase = true) }

        fun fromStorageKey(key: String?): DnsExperimentMode = fromStorageKeyOrNull(key) ?: DEFAULT
    }
}

/** A route the tunnel installs for its DNS server. */
data class DnsRoute(val address: String, val prefixLength: Int)

/**
 * What the tunnel should declare, kept separate from [VpnRoutePlan] so the shipped route policy is
 * never loosened for a debug experiment.
 *
 * A null [dnsServer] means "do not call `addDnsServer`"; a null [dnsRoute] means "do not add a route
 * for the DNS server". The interface address is always kept so the IPv4 family stays declared and
 * cannot end up blocked.
 */
data class TunnelDeclaration(
    val interfaceAddress: String,
    val interfacePrefixLength: Int,
    val dnsServer: String?,
    val dnsRoute: DnsRoute?,
    val mtu: Int
) {
    /** Every experiment must keep this invariant: a catch-all route would capture all traffic. */
    fun hasCatchAllRoute(): Boolean =
        interfacePrefixLength == 0 || dnsRoute?.prefixLength == 0
}

/**
 * Pure builder for the three experiment configurations. It never touches Android APIs, so the whole
 * matrix is unit testable without a device.
 */
object DnsExperimentPlanner {

    /** The shipped declaration for [baseline]; never null. */
    fun baselineDeclaration(baseline: VpnRoutePlan): TunnelDeclaration = TunnelDeclaration(
        interfaceAddress = baseline.interfaceAddress,
        interfacePrefixLength = baseline.interfacePrefixLength,
        dnsServer = baseline.dnsServerAddress,
        dnsRoute = DnsRoute(baseline.dnsServerAddress, baseline.dnsPrefixLength),
        mtu = baseline.mtu
    )

    /**
     * Builds the declaration for [mode].
     *
     * Returns null when the configuration cannot be applied safely — currently only E1 without a
     * usable real DoT address (missing, not a valid IPv4 literal, or the virtual DNS address itself,
     * which would just reproduce the broken case). A null result means "do not establish a tunnel".
     */
    fun declarationFor(
        mode: DnsExperimentMode,
        baseline: VpnRoutePlan,
        realDotAddress: String?
    ): TunnelDeclaration? = when (mode) {
        DnsExperimentMode.BASELINE -> baselineDeclaration(baseline)

        DnsExperimentMode.E2_NO_DNS_DECLARATION -> baselineDeclaration(baseline).copy(dnsServer = null)

        DnsExperimentMode.E1_REAL_DOT_ADDRESS -> {
            val address = realDotAddress?.trim().orEmpty()
            when {
                address.isEmpty() -> null
                Bytes.addressFromText(address) == null -> null
                address == baseline.dnsServerAddress -> null
                else -> baselineDeclaration(baseline).copy(
                    dnsServer = address,
                    // Deliberately no route for the real address: the experiment asks whether the
                    // resolver then reaches the real DoT server over the underlying network.
                    dnsRoute = null
                )
            }
        }
    }
}

/**
 * Resolves the configuration actually used for a start.
 *
 * The shipped coexistence rule applies to every build. A debug build may additionally arm an
 * override for the current process only — it is never stored, so a leftover can never quietly
 * replace the rule in a later session, and a release build has no way to arm one at all.
 */
object DnsExperiment {

    /** Legacy keys from the first experiment build; the stored mode is deleted, never read. */
    internal const val LEGACY_PREFS = "kuiesblock_debug_experiment"
    internal const val LEGACY_KEY_MODE = "dns_experiment_mode"

    @Volatile
    private var armedOverride: DnsExperimentMode? = null

    @Volatile
    private var legacyCleared = false

    /** The mode a debug session armed for this process, or null when the shipped rule applies. */
    fun armedMode(): DnsExperimentMode? = armedOverride

    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Pure form of [mode]: a non-debuggable build can never escape the coexistence rule, and a debug
     * build can only escape it through an override explicitly armed for this process.
     */
    fun effectiveMode(
        isDebuggable: Boolean,
        armed: DnsExperimentMode?,
        strictPrivateDns: Boolean
    ): DnsExperimentMode {
        val coexistenceDefault =
            if (strictPrivateDns) DnsExperimentMode.E2_NO_DNS_DECLARATION else DnsExperimentMode.BASELINE
        if (!isDebuggable) return coexistenceDefault
        return armed ?: coexistenceDefault
    }

    /**
     * The configuration actually used for this start.
     *
     * The shipped rule is that only strict Private DNS steps aside: there the tunnel must not declare
     * a DNS server, because the system resolver would then open DoT to our own address and nothing
     * could answer it. Every other mode keeps the intercepting BASELINE. A debug session may arm an
     * override to reproduce the broken case or to run the E1 experiment.
     */
    fun mode(context: Context, strictPrivateDns: Boolean): DnsExperimentMode {
        clearLegacyStoredMode(context)
        return effectiveMode(isDebuggable(context), armedOverride, strictPrivateDns)
    }

    /**
     * Debug only; the debug source set's receiver is the only caller. The value lives only as long as
     * this app process — nothing is stored, so nothing can survive into a later session. That is the
     * fix for the stale experiment that quietly replaced the shipped rule.
     */
    fun arm(context: Context, mode: DnsExperimentMode) {
        if (!isDebuggable(context)) return
        armedOverride = mode
    }

    /**
     * The first experiment build persisted its mode, which then silently overrode the shipped rule in
     * a much later session. The key is deleted so that stale value can never be read again; it was
     * never a supported storage location.
     */
    private fun clearLegacyStoredMode(context: Context) {
        if (legacyCleared || !isDebuggable(context)) return
        legacyCleared = true
        runCatching {
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit()
                .remove(LEGACY_KEY_MODE)
                .apply()
        }
    }
}
