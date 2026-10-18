package tw.kuies.block.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The last failure reported by the system PackageInstaller callback, for the open update dialog. */
object UpdateInstallStatus {
    private val _failure = MutableStateFlow<UpdateInstallFailure?>(null)
    val failure: StateFlow<UpdateInstallFailure?> = _failure.asStateFlow()

    fun report(reason: UpdateInstallFailure) {
        _failure.value = reason
    }

    fun clear() {
        _failure.value = null
    }
}
