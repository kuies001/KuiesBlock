package tw.kuies.block

import android.content.SharedPreferences

/**
 * A `SharedPreferences` for JVM unit tests.
 *
 * It reproduces the parts of Android's behaviour the stores depend on: reads are typed (asking for a
 * type other than the one that was stored throws, exactly as the platform does), returned sets are
 * copies that the caller may modify freely, and `putString(key, null)` removes the key the way
 * `commit()` does on a device. [rawPut] exists so a test can plant a value of an unexpected type.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = LinkedHashMap<String, Any>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? = typed<String>(key) ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        typed<Set<String>>(key)?.let { LinkedHashSet(it) }
            ?: defValues?.let { LinkedHashSet(it) }

    override fun getInt(key: String?, defValue: Int): Int = typed<Int>(key) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = typed<Long>(key) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = typed<Float>(key) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = typed<Boolean>(key) ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listeners.remove(listener)
    }

    /** Plants a value of any type, including one the store has no business reading. */
    fun rawPut(key: String, value: Any) {
        values[key] = value
    }

    fun rawRemove(key: String) {
        values.remove(key)
    }

    private inline fun <reified T> typed(key: String?): T? {
        val value = values[key] ?: return null
        if (value !is T) throw ClassCastException("$key holds ${value.javaClass.name}")
        return value
    }

    private inner class Editor : SharedPreferences.Editor {

        private val pending = LinkedHashMap<String, Any>()
        private val removals = LinkedHashSet<String>()
        private var clearPending = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            stage(key, value)

        override fun putStringSet(
            key: String?,
            values: Set<String>?
        ): SharedPreferences.Editor = stage(key, values?.let { LinkedHashSet(it) })

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = stage(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = stage(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = stage(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            stage(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = stage(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearPending = true
            pending.clear()
            removals.clear()
            return this
        }

        override fun commit(): Boolean {
            commitToMemory()
            return true
        }

        override fun apply() = commitToMemory()

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor {
            if (key == null) return this
            if (value == null) {
                pending.remove(key)
                removals.add(key)
            } else {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        private fun commitToMemory() {
            if (clearPending) {
                values.clear()
                clearPending = false
            }
            for (key in removals) values.remove(key)
            for ((key, value) in pending) values[key] = value
            pending.clear()
            removals.clear()
            val changed = listeners.toList()
            for (listener in changed) listener.onSharedPreferenceChanged(this@FakeSharedPreferences, null)
        }
    }
}
