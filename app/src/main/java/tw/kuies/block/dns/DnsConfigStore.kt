package tw.kuies.block.dns

import android.content.Context

/**
 * Single place that reads and writes the DNS endpoint + protocol settings, so the rest of the
 * app never touches the preferences directly.
 */
class DnsConfigStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): DnsConfig = DnsConfig(
        host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: DnsConfig.DEFAULT_HOST,
        // The stored transport is clamped to the implemented one: an install carrying a value from
        // the removed picker must not be stuck with protection that can never start.
        protocol = DnsProtocol.implementedOrDoh(
            DnsProtocol.fromStorageKey(prefs.getString(KEY_PROTOCOL, null))
        ),
        dohPath = prefs.getString(KEY_DOH_PATH, null)?.takeIf { it.isNotBlank() }
            ?: DnsConfig.DEFAULT_DOH_PATH
    )

    fun save(config: DnsConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putString(KEY_PROTOCOL, config.protocol.storageKey)
            .putString(KEY_DOH_PATH, config.dohPath)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "kuies_block_dns"
        private const val KEY_HOST = "host"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_DOH_PATH = "doh_path"
    }
}
