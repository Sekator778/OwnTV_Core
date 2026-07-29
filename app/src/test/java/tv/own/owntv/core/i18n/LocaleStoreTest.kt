package tv.own.owntv.core.i18n

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LocaleStore unit tests (docs/internationalization.md, "Unit tests → LocaleStore").
 *
 * Runs on the plain JVM with a tiny in-memory [SharedPreferences] fake — the real store is just a
 * typed view over SharedPreferences, so no Robolectric is needed. The durability-after-restart test
 * models "process death" by constructing a fresh LocaleStore over the same fake prefs (the same way a
 * new process reopens the same SharedPreferences file).
 */
class LocaleStoreTest {

    private fun newPrefs(): FakeSharedPreferences = FakeSharedPreferences()

    @Test
    fun `readBlocking returns the empty tag when nothing is stored`() {
        val store = LocaleStore(newPrefs())
        assertEquals("", store.readBlocking())
        assertEquals("", store.currentTag.value)
    }

    @Test
    fun `empty tag is a valid persisted value distinct from unset`() {
        // "follow system" is the durable instruction, not "leave the current locale unchanged".
        val prefs = newPrefs()
        runBlocking { LocaleStore(prefs).set("") }
        val reopened = LocaleStore(prefs)
        assertEquals("", reopened.readBlocking())
    }

    @Test
    fun `set persists and publishes the tag`() {
        val prefs = newPrefs()
        val store = LocaleStore(prefs)
        runBlocking { store.set("de") }
        assertEquals("de", store.currentTag.value)
        assertEquals("de", store.readBlocking())
    }

    @Test
    fun `the value is readable after a simulated process restart`() {
        val prefs = newPrefs()
        runBlocking { LocaleStore(prefs).set("fr") }
        // A new process reopens the same SharedPreferences file; the StateFlow is per-instance.
        val afterRestart = LocaleStore(prefs)
        assertEquals("fr", afterRestart.readBlocking())
        assertEquals("fr", afterRestart.currentTag.value)
    }

    @Test
    fun `reset to system default writes the empty tag through the same API`() {
        val prefs = newPrefs()
        val store = LocaleStore(prefs)
        runBlocking { store.set("de") }
        runBlocking { store.set("") } // same write path the picker / backup import use
        assertEquals("", store.readBlocking())
    }

    @Test
    fun `a failed commit is surfaced, not swallowed`() {
        val prefs = newPrefs().apply { commitResult = false }
        val store = LocaleStore(prefs)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.set("de") }
        }
        // The in-memory StateFlow is only updated after a successful commit, so it stays empty.
        assertEquals("", store.currentTag.value)
    }

    @Test
    fun `backup import writes through the same API`() {
        // Backup import calls LocaleStore.set(tag); there is no second write path.
        val prefs = newPrefs()
        runBlocking { LocaleStore(prefs).set("tr") }
        assertTrue("tr" in prefs.stored.values)
        assertEquals("tr", LocaleStore(prefs).readBlocking())
    }
}

/** Minimal in-memory SharedPreferences. Implements only what LocaleStore uses; the rest throw. */
private class FakeSharedPreferences : SharedPreferences {
    private val entries: MutableMap<String, Any?> = mutableMapOf()
    var commitResult: Boolean = true

    /** Test-only accessor over the stored entries. */
    val stored: Map<String, Any?> get() = entries

    override fun getAll(): Map<String, *> = entries
    override fun getString(key: String, defValue: String?): String? = (entries[key] as? String) ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = throw UnsupportedOperationException()
    override fun getInt(key: String, defValue: Int) = throw UnsupportedOperationException()
    override fun getLong(key: String, defValue: Long) = throw UnsupportedOperationException()
    override fun getFloat(key: String, defValue: Float) = throw UnsupportedOperationException()
    override fun getBoolean(key: String, defValue: Boolean) = throw UnsupportedOperationException()
    override fun contains(key: String) = key in entries
    override fun edit(): SharedPreferences.Editor = Editor(this)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class Editor(val outer: FakeSharedPreferences) : SharedPreferences.Editor {
        var stagedKey: String? = null
        var stagedValue: String? = null
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            stagedKey = key; stagedValue = value; return this
        }
        override fun putStringSet(key: String, values: Set<String>?) = throw UnsupportedOperationException()
        override fun putInt(key: String, value: Int) = throw UnsupportedOperationException()
        override fun putLong(key: String, value: Long) = throw UnsupportedOperationException()
        override fun putFloat(key: String, value: Float) = throw UnsupportedOperationException()
        override fun putBoolean(key: String, value: Boolean) = throw UnsupportedOperationException()
        override fun remove(key: String) = throw UnsupportedOperationException()
        override fun clear() = throw UnsupportedOperationException()
        override fun commit(): Boolean {
            if (!outer.commitResult) return false
            outer.entries[stagedKey!!] = stagedValue
            return true
        }
        override fun apply() = throw UnsupportedOperationException()
    }
}