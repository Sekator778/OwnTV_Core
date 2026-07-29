package tv.own.owntv.core.i18n

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The single source of truth for the selected application locale, as a BCP-47 tag. `""`
 * ([AppLocale.SYSTEM_DEFAULT_TAG]) means "follow the current device locale list".
 *
 * **Why SharedPreferences and not DataStore** (see `docs/internationalization.md` 0b): the selected
 * locale must be readable *synchronously* from `Application.attachBaseContext` and
 * `Activity.attachBaseContext`. DataStore is asynchronous and cannot be read cleanly from those
 * lifecycle hooks without blocking or redesigning startup. This one bootstrap-critical setting
 * therefore lives in SharedPreferences — alone, with no DataStore mirror, no dual write, and no
 * mirror-repair collector (all of v1's reconciliation complexity is gone because OwnTV no longer
 * participates in the Android system per-app-language screen).
 *
 * Every locale write goes through [set]: the in-app picker, "reset to system default" (`""`),
 * backup import, and any settings-reset operation. There is no second write path.
 */
class LocaleStore internal constructor(
    private val preferences: SharedPreferences,
    /**
     * The application context for [applyGlobally] after an in-session write, or null for the throwaway
     * read-only instances built in `attachBaseContext` (which only call [readBlocking]; a write there
     * would be a bug). The Koin singleton is constructed with the real `Application` so writes update
     * the process `Locale` defaults immediately (see [set]).
     */
    private val applicationContext: Context?,
) {

    private val _currentTag: MutableStateFlow<String> = MutableStateFlow(readBlocking())

    /** The currently selected tag, observable in-process. `""` means follow system. */
    val currentTag: StateFlow<String> = _currentTag.asStateFlow()

    /**
     * Synchronous read of the persisted tag. Safe to call from `attachBaseContext`.
     * Returns `""` (never null) when nothing is stored — i.e. follow the system default.
     */
    fun readBlocking(): String = preferences.getString(KEY_UI_LANGUAGE, "").orEmpty()

    /**
     * Durably persists [tag], publishes it to [currentTag], and re-applies the process `Locale` /
     * `LocaleList` defaults so `java.text`, `java.time`, `String.format` and every other
     * `Locale.getDefault()` reader follow the new selection immediately — not on the next unrelated
     * configuration callback. `LocalizedContent` handles the Compose locals and the script-family
     * `Activity.recreate()`; this handles the non-Compose `Locale.getDefault()` readers (see
     * `docs/internationalization.md` 0b, "The write path updates process defaults").
     *
     * Uses `commit()` (synchronous, durable) off the main thread so the operation only returns after
     * the value is on disk — the locale is needed on the *next cold start*, so durability is required,
     * not "best effort".
     *
     * Returns `true` when the write was committed. A `false` (failed `commit`) is surfaced as an
     * [IllegalStateException] rather than swallowed: a silent locale-write failure would leave the
     * user thinking they switched language while nothing persisted.
     */
    suspend fun set(tag: String): Boolean {
        val committed = withContext(Dispatchers.IO) {
            preferences.edit().putString(KEY_UI_LANGUAGE, tag).commit()
        }
        check(committed) { "Failed to persist application locale" }
        _currentTag.value = tag
        applicationContext?.let { AppLocale.applyGlobally(tag, it) }
        return true
    }

    companion object {
        private const val KEY_UI_LANGUAGE = "ui_language"
        private const val PREFS_NAME = "owntv_locale"

        /**
         * A [LocaleStore] over the application-scoped `owntv_locale` SharedPreferences. Used both at
         * cold start (Koin is not yet started in `attachBaseContext`, so callers there build this
         * directly from the base context) and as the shared Koin singleton afterwards — the file is
         * the same, so the persisted value is always consistent. The in-process [StateFlow] is
         * per-instance, so callers that must observe writes (the picker, renderers) take the Koin
         * singleton rather than building their own.
         *
         * **Does not call `context.applicationContext`.** During `Application.attachBaseContext` the
         * framework has not yet assigned `LoadedApk.mApplication`, so `getApplicationContext()` returns
         * null and the store crashes before `onCreate`. The supplied context's own
         * `getSharedPreferences` opens the same package-private file regardless of which context in the
         * hierarchy is used, so the base context is used directly. The Koin singleton is built from the
         * fully-initialised `Application` after `startKoin`, where `applicationContext` is safe.
         */
        fun from(context: Context): LocaleStore {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // applicationContext is null until the Application is constructed; in attachBaseContext the
            // base context is not yet an Application, and context.applicationContext returns null there.
            // Only a fully-initialised Application (the Koin singleton path) carries it, so writes can
            // apply globally. Read-only attachBaseContext instances get null and never write.
            val app = context.applicationContext
            return LocaleStore(prefs, app)
        }
    }
}