package tw.kuies.block.data

/**
 * Small, non-sensitive settings blob.
 *
 * KuiesBlock deliberately keeps no accounts, identifiers, or history, so this is the whole
 * persisted state besides the DNS configuration.
 */
data class AppSettings(
    /** Last known VPN authorization state, used only to render the UI before `prepare()` is re-checked. */
    val vpnAuthorizationGranted: Boolean = false
)
