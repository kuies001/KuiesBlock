package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kuies.block.dns.DnsUpstreamRole
import tw.kuies.block.dns.DnsUpstreamServingPolicy

/** The query path must never keep using a transport that fail-open or the user has abandoned. */
class DnsUpstreamServingPolicyTest {

    @Test
    fun failOpenServesNothing() {
        assertNull(DnsUpstreamServingPolicy.servingRole(true, true, DnsUpstreamRole.PRIMARY))
        assertNull(DnsUpstreamServingPolicy.servingRole(true, false, DnsUpstreamRole.BACKUP))
    }

    @Test
    fun disabledBackupIsNotServed() {
        assertNull(DnsUpstreamServingPolicy.servingRole(false, false, DnsUpstreamRole.BACKUP))
    }

    @Test
    fun disabledPrimaryStillServes() {
        assertEquals(
            DnsUpstreamRole.PRIMARY,
            DnsUpstreamServingPolicy.servingRole(false, false, DnsUpstreamRole.PRIMARY)
        )
    }

    @Test
    fun enabledRoleIsUnchanged() {
        assertEquals(
            DnsUpstreamRole.PRIMARY,
            DnsUpstreamServingPolicy.servingRole(false, true, DnsUpstreamRole.PRIMARY)
        )
        assertEquals(
            DnsUpstreamRole.BACKUP,
            DnsUpstreamServingPolicy.servingRole(false, true, DnsUpstreamRole.BACKUP)
        )
    }
}
