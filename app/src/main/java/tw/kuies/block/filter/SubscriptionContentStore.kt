package tw.kuies.block.filter

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.text.Charsets

/**
 * The downloaded list text of each subscription, one file per source under `<filesDir>/filters`.
 *
 * A subscription id is used as a file name, so [safeId] allows only a conservative character set: an
 * id can never become a path, escape the directory, or name a device on a host filesystem.
 *
 * [write] always stages the text in a sibling `.tmp` file and then replaces the previous copy, so a
 * crash, a full disk or a failed rename can only ever leave the *old* content in place — never a
 * half-written list that would parse into a truncated rule set.
 */
class SubscriptionContentStore(private val rootDir: File) {

    /** The file holding [subscriptionId]'s list, or null when the id is not usable as a name. */
    fun fileFor(subscriptionId: String): File? {
        val safe = safeId(subscriptionId) ?: return null
        return File(rootDir, "$safe$FILE_SUFFIX")
    }

    fun exists(subscriptionId: String): Boolean = fileFor(subscriptionId)?.isFile == true

    /** The stored text, or null when there is none or it cannot be read. */
    fun read(subscriptionId: String): String? {
        val file = fileFor(subscriptionId) ?: return null
        return try {
            if (file.isFile) file.readText() else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Replaces the stored text atomically; false when nothing was written. */
    fun write(subscriptionId: String, text: String): Boolean {
        val target = fileFor(subscriptionId) ?: return false
        val directory = target.parentFile ?: return false

        return try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
            val temp = File(directory, "${target.name}$TEMP_SUFFIX")
            temp.writeText(text, Charsets.UTF_8)
            try {
                replace(temp, target)
                true
            } catch (_: IOException) {
                temp.delete()
                false
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun delete(subscriptionId: String): Boolean {
        val file = fileFor(subscriptionId) ?: return false
        return try {
            !file.exists() || file.delete()
        } catch (_: SecurityException) {
            false
        }
    }

    private fun replace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val DIRECTORY_NAME = "filters"
        private const val FILE_SUFFIX = ".list"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_ID_LENGTH = 64

        /** Names a host filesystem treats as a device, whatever extension they carry. */
        private val RESERVED_NAMES = setOf(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
        )

        /** The store the app uses: `<filesDir>/filters`. */
        fun forFilesDir(filesDir: File): SubscriptionContentStore =
            SubscriptionContentStore(File(filesDir, DIRECTORY_NAME))

        /**
         * An id usable as a file name: letters, digits, dot, underscore and dash only, no leading or
         * trailing dot run (which is what `..` and hidden names would need).
         */
        fun safeId(subscriptionId: String): String? {
            if (subscriptionId.isEmpty() || subscriptionId.length > MAX_ID_LENGTH) return null
            if (subscriptionId.startsWith('.')) return null
            val allowed = subscriptionId.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
            }
            if (!allowed) return null
            if (subscriptionId.lowercase() in RESERVED_NAMES) return null
            return subscriptionId
        }
    }
}
