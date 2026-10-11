package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.vpn.VpnRoutePlan
import tw.kuies.block.vpn.VpnRoutePlanPolicy

class VpnRoutePlanPolicyTest {

    @Test
    fun theDefaultPlanIsSafeToApply() {
        assertTrue(VpnRoutePlanPolicy.problemsWith(VpnRoutePlan.DEFAULT).isEmpty())
        assertTrue(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT))
    }

    @Test
    fun theDefaultPlanContainsOnlyTheDnsHostRoute() {
        assertEquals("10.111.222.2", VpnRoutePlan.DEFAULT.dnsServerAddress)
        assertEquals(32, VpnRoutePlan.DEFAULT.dnsPrefixLength)
        assertEquals(32, VpnRoutePlan.DEFAULT.interfacePrefixLength)
        assertFalse(VpnRoutePlanPolicy.isCatchAllRoute(VpnRoutePlan.DEFAULT.dnsPrefixLength))
    }

    @Test
    fun aDefaultRouteIsRejected() {
        assertTrue(VpnRoutePlanPolicy.isCatchAllRoute(0))
        assertFalse(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT.copy(dnsPrefixLength = 0)))
        assertFalse(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT.copy(interfacePrefixLength = 0)))
    }

    @Test
    fun aWiderDnsRouteIsRejected() {
        assertFalse(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT.copy(dnsPrefixLength = 16)))
        assertFalse(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT.copy(dnsPrefixLength = 24)))
    }

    @Test
    fun anInvalidAddressOrMtuIsRejected() {
        assertFalse(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT.copy(dnsServerAddress = "not-an-ip")))
        assertFalse(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT.copy(interfaceAddress = "::1")))
        assertFalse(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT.copy(mtu = 100)))
        assertFalse(VpnRoutePlanPolicy.isValid(VpnRoutePlan.DEFAULT.copy(dnsPort = 0)))
    }

    @Test
    fun theInterceptTargetMatchesThePlan() {
        val target = VpnRoutePlan.DEFAULT.interceptTarget
        assertEquals(ipAddress("10.111.222.2"), target.address)
        assertEquals(53, target.port)
    }
}
