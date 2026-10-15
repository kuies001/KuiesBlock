package tw.kuies.block

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.kuies.block.filter.SubscriptionContentStore

/**
 * The stored list files.
 *
 * Two things matter: an id is never allowed to become a path, and replacing a copy never leaves a
 * half-written file behind.
 */
class SubscriptionContentStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var store: SubscriptionContentStore

    @Before
    fun setUp() {
        store = SubscriptionContentStore(
            File(temporaryFolder.root, SubscriptionContentStore.DIRECTORY_NAME)
        )
    }

    @Test
    fun aListIsStoredUnderItsIdAndReadBack() {
        assertFalse(store.exists("mine"))
        assertNull(store.read("mine"))

        assertTrue(store.write("mine", "||ads.example^\n"))

        assertTrue(store.exists("mine"))
        assertEquals("||ads.example^\n", store.read("mine"))
        assertEquals(
            File(File(temporaryFolder.root, SubscriptionContentStore.DIRECTORY_NAME), "mine.list"),
            store.fileFor("mine")
        )
    }

    @Test
    fun writingAgainReplacesTheCopyAndLeavesNoStagingFile() {
        store.write("mine", "old\n")
        store.write("mine", "new\n")

        assertEquals("new\n", store.read("mine"))
        val directory = requireNotNull(requireNotNull(store.fileFor("mine")).parentFile)
        assertFalse(
            "a staging file must never survive a write",
            File(directory, "mine.list.tmp").exists()
        )
        assertEquals(listOf("mine.list"), directory.list()!!.toList())
    }

    @Test
    fun aStoredListCanBeDeletedAndIsThenGone() {
        store.write("mine", "new\n")

        assertTrue(store.delete("mine"))
        assertFalse(store.exists("mine"))
        assertNull(store.read("mine"))
    }

    @Test
    fun anIdThatCouldEscapeTheDirectoryIsRefused() {
        val refused = listOf(
            "../escape",
            "..",
            ".",
            "dir/child",
            "dir\\child",
            "C:mine",
            "mine:list",
            "",
            "mine list",
            "mine\nlist",
            "con",
            "NUL",
            "com1",
            "a".repeat(65)
        )

        for (id in refused) {
            assertNull("$id must not become a file name", SubscriptionContentStore.safeId(id))
            assertNull(store.fileFor(id))
            assertFalse("nothing may be written for $id", store.write(id, "content"))
            assertFalse(store.exists(id))
            assertNull(store.read(id))
        }
    }

    @Test
    fun ordinaryIdsAreAccepted() {
        assertEquals("hagezi.profile", SubscriptionContentStore.safeId("hagezi.profile"))
        assertEquals("mine-1_v2", SubscriptionContentStore.safeId("mine-1_v2"))
    }

    @Test
    fun theStoreIsCreatedNextToTheFilesDirectory() {
        val filesDir = File(temporaryFolder.root, "files")
        val created = SubscriptionContentStore.forFilesDir(filesDir)

        assertEquals(
            File(File(filesDir, SubscriptionContentStore.DIRECTORY_NAME), "mine.list"),
            created.fileFor("mine")
        )
    }
}
