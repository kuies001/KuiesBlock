package tw.kuies.block.doh

/**
 * Non-sensitive classification of why a DoH exchange failed.
 *
 * These values may be logged and shown in diagnostics; [DohResult.Failure.detail] must never
 * carry DNS query content or user data.
 */
enum class DohFailure {
    /** The configured protocol is not DoH, so there is nothing to send. */
    PROTOCOL_NOT_DOH,
    /** The endpoint could not be turned into a usable HTTPS URL. */
    INVALID_ENDPOINT,
    /** The connection could not be established (no route, DNS failure, refused). */
    CONNECT_FAILED,
    /** TLS could not be trusted or validated. */
    TLS_FAILED,
    /** The request timed out. */
    TIMEOUT,
    /** The server answered with a non-200 status. */
    HTTP_STATUS,
    /** The response was not `application/dns-message`. */
    CONTENT_TYPE,
    /** The response body was empty. */
    EMPTY_BODY,
    /** The response body exceeded the sane DoH size cap. */
    TOO_LARGE,
    /** The body was not a well-formed DNS response. */
    MALFORMED_RESPONSE,
    /** The response transaction id did not match the query. */
    TRANSACTION_ID_MISMATCH,
    /** Any other I/O problem. */
    IO
}

sealed interface DohResult {
    data class Success(val responseBytes: ByteArray) : DohResult

    data class Failure(
        val failure: DohFailure,
        val httpStatus: Int? = null,
        val detail: String? = null
    ) : DohResult
}
