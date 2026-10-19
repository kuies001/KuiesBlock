package tw.kuies.block.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tw.kuies.block.dns.DnsFallbackStatus

/**
 * Process-wide, observable protection status shared by the VPN service and the Compose UI.
 *
 * A single snapshot-backed holder is enough to keep both sides in sync without a DI framework.
 * All writes happen on the main thread (service callbacks post there).
 */
object ProtectionStatusHolder {

    var state: ProtectionState by mutableStateOf(ProtectionState.NOT_AUTHORIZED)
        private set

    /** The reason protection is in [ProtectionState.ERROR], or null in any other state. */
    var error: ProtectionError? by mutableStateOf(null)
        private set

    /**
     * Which DNS path is actually in use. Defaults to interception, so the baseline path and every
     * existing caller are unchanged; only the strict Private DNS start reports the other value.
     */
    var dnsPath: DnsPath by mutableStateOf(DnsPath.INTERCEPTED)
        private set

    /**
     * Which encrypted upstream the service is really using, as last verified by the service.
     *
     * The holder only publishes; the UI must still check [ProtectionState], because a stopped or
     * starting tunnel can never be described as 「主要 DNS 正常」.
     */
    var dnsFallbackStatus: DnsFallbackStatus by mutableStateOf(DnsFallbackStatus.PRIMARY)
        private set

    fun updateDnsFallback(status: DnsFallbackStatus) {
        dnsFallbackStatus = status
    }

    fun update(
        newState: ProtectionState,
        errorReason: ProtectionError? = null,
        path: DnsPath = DnsPath.INTERCEPTED
    ) {
        state = newState
        error = errorReason
        dnsPath = path
    }
}
