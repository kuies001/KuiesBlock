package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.vpn.SystemUidExclusion

/**
 * The tunnel must always exclude the platform UID, because Android renders both the wireless
 * debugging address and the Private DNS status from the system process's own default network.
 * The user's own exclusions must survive unchanged, minus this app and duplicates.
 */
class SystemUidExclusionTest {

    @Test
    fun platformUidIsAlwaysExcluded() {
        assertTrue(SystemUidExclusion.forTunnel(emptySet(), "tw.kuies.block").contains("android"))
    }

    @Test
    fun userExclusionsAreKept() {
        val effective = SystemUidExclusion.forTunnel(setOf("com.example.browser"), "tw.kuies.block")
        assertEquals(listOf("android", "com.example.browser"), effective)
    }

    @Test
    fun thisAppIsNeverExcluded() {
        assertFalse(SystemUidExclusion.forTunnel(setOf("tw.kuies.block"), "tw.kuies.block").contains("tw.kuies.block"))
    }

    @Test
    fun aUserEntryCannotDuplicateTheSystemPackage() {
        val effective = SystemUidExclusion.forTunnel(setOf("android", "com.example.browser"), "tw.kuies.block")
        assertEquals(listOf("android", "com.example.browser"), effective)
    }

    @Test
    fun systemPackagesAreRecognisedByName() {
        assertTrue(SystemUidExclusion.isSystem("android"))
        assertFalse(SystemUidExclusion.isSystem("com.example.browser"))
    }
}
