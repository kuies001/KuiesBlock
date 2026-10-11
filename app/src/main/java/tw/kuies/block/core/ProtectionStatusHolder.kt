package tw.kuies.block.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    fun update(newState: ProtectionState, errorReason: ProtectionError? = null) {
        state = newState
        error = errorReason
    }
}
