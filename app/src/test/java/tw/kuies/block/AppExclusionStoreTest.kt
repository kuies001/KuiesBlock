package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.data.AppExclusionStore

class AppExclusionStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = AppExclusionStore(prefs)

    @Test
    fun defaultsToEmpty() {
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun savesAndReloadsPackageNames() {
        val packages = setOf("com.example.two", "com.example.one")
        store.save(packages)

        assertEquals(packages, store.load())
        assertEquals(packages, AppExclusionStore(prefs).load())
    }

    @Test
    fun clearRestoresTheDefault() {
        store.save(setOf("com.example.one"))

        store.clear()

        assertTrue(store.load().isEmpty())
    }
}
