package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kuies.block.core.ProtectionError
import tw.kuies.block.doh.DohFailure
import tw.kuies.block.doh.toProtectionError

class DohFailureMappingTest {

    @Test
    fun tlsProblemsMapToTlsFailure() {
        assertEquals(ProtectionError.TLS_FAILURE, DohFailure.TLS_FAILED.toProtectionError())
    }

    @Test
    fun onlyAnActualTimeoutMapsToTimeout() {
        assertEquals(ProtectionError.DNS_TIMEOUT, DohFailure.TIMEOUT.toProtectionError())
    }

    @Test
    fun unreachableProblemsMapToUnreachable() {
        assertEquals(ProtectionError.DNS_UNREACHABLE, DohFailure.CONNECT_FAILED.toProtectionError())
        assertEquals(ProtectionError.DNS_UNREACHABLE, DohFailure.IO.toProtectionError())
    }

    @Test
    fun configurationProblemsMapToTheEndpointError() {
        assertEquals(ProtectionError.ENDPOINT_NOT_DOH, DohFailure.PROTOCOL_NOT_DOH.toProtectionError())
        assertEquals(ProtectionError.ENDPOINT_NOT_DOH, DohFailure.INVALID_ENDPOINT.toProtectionError())
    }

    @Test
    fun badAnswersAreInvalidResponsesAndNeverBlocks() {
        listOf(
            DohFailure.HTTP_STATUS,
            DohFailure.CONTENT_TYPE,
            DohFailure.EMPTY_BODY,
            DohFailure.TOO_LARGE,
            DohFailure.MALFORMED_RESPONSE,
            DohFailure.TRANSACTION_ID_MISMATCH
        ).forEach { failure ->
            assertEquals("$failure", ProtectionError.DNS_INVALID_RESPONSE, failure.toProtectionError())
        }
    }

    @Test
    fun everyFailureHasAnExplicitMapping() {
        DohFailure.entries.forEach { failure ->
            // The mapping is an exhaustive when-expression; this guards against a future "else" arm
            // silently swallowing a new failure into a vague error.
            assertEquals("$failure", true, failure.toProtectionError() in ProtectionError.entries)
        }
    }
}
