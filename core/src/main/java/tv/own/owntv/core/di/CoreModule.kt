package tv.own.owntv.core.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The bindings core owns, so any app built on core gets them by including one module rather than
 * re-declaring them. Everything a TV/phone shell owns stays in that app's own modules.
 */
val coreModule = module {
    // The single locale authority (SharedPreferences-backed; see LocaleStore). Registered first so the
    // SettingsRepository binding below resolves it. The same instance observes the in-process StateFlow
    // for the picker and the named non-Compose renderers.
    single { LocaleStore.from(androidContext()) }
    single { SettingsRepository(androidContext(), get()) }
}
