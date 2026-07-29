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
     * Durably persists [tag] and publishes it to [currentTag]. Uses `commit()` (synchronous, durable)
     * off the main thread so the operation only returns after the value is on disk — the locale is
     * needed on the *next cold start*, so durability is required, not "best effort".
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
         */
        fun from(context: Context): LocaleStore {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return LocaleStore(prefs)
        }
    }
}