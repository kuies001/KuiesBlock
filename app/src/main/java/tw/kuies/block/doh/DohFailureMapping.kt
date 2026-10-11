package tw.kuies.block.doh

import tw.kuies.block.core.ProtectionError

/**
 * Maps a transport failure onto the coarse, user-facing protection error.
 *
 * Keeping the mapping here (and pure) means the fail-open decision and its wording are testable
 * without a device, and a new transport failure cannot silently fall through to a vague default.
 */
fun DohFailure.toProtectionError(): ProtectionError = when (this) {
    DohFailure.PROTOCOL_NOT_DOH, DohFailure.INVALID_ENDPOINT -> ProtectionError.ENDPOINT_NOT_DOH
    DohFailure.TLS_FAILED -> ProtectionError.TLS_FAILURE
    DohFailure.TIMEOUT -> ProtectionError.DNS_TIMEOUT
    DohFailure.CONNECT_FAILED, DohFailure.IO -> ProtectionError.DNS_UNREACHABLE
    DohFailure.HTTP_STATUS,
    DohFailure.CONTENT_TYPE,
    DohFailure.EMPTY_BODY,
    DohFailure.TOO_LARGE,
    DohFailure.MALFORMED_RESPONSE,
    DohFailure.TRANSACTION_ID_MISMATCH -> ProtectionError.DNS_INVALID_RESPONSE
}
