package tw.kuies.block.filter

/**
 * The rule snapshot the VPN consults, shared process-wide.
 *
 * The service reads it on the query path while the settings and subscription layers publish into it
 * from the UI and from background updates; both sides must therefore see the same instance. This
 * mirrors how `ProtectionStatusHolder` shares protection state between the service and the UI.
 *
 * Reads are lock-free because [FilterRepository] only ever swaps one immutable snapshot for another.
 */
object FilterSnapshotHolder {

    val repository = FilterRepository()

    /** Convenience for the packet path: the rules to apply right now. */
    fun snapshot(): FilterSnapshot = repository.snapshot()
}
