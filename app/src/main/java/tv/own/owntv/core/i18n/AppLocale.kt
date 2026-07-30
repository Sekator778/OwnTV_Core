package tv.own.owntv.core.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * Locale runtime helpers. Two operations, both verified against compose-ui 1.11.1 bytecode, AGP
 * 9.2.1 and android-36.1 sources (see `docs/internationalization.md`, "Verified mechanics"):
 *
 *  - [wrap] returns a `Context` whose `Resources` resolve strings for the effective locale list.
 *  - [applyGlobally] sets the process `Locale`/`LocaleList` defaults so `java.text`, `java.time`,
 *    `String.format` and every other `Locale.getDefault()` reader follow the selection.
 *
 * `""` ([SYSTEM_DEFAULT_TAG]) means "follow the current device locale list". The device list is read
 * from `Resources.getSystem().configuration.locales` — the process-wide system singleton — which is
 * used **only** for device-locale metadata here, never for app strings (app strings resolve from the
 * wrapped context). Selecting "System default" is therefore a durable instruction to follow the TV's
 * locale list, not "leave the current app locale unchanged".
 */
object AppLocale {

    /** The stored tag value meaning "follow the current device locale list". */
    const val SYSTEM_DEFAULT_TAG = ""

    /**
     * The effective `LocaleList` for [tag]. Empty tag → the device's current locale list. Non-empty
     * tag → the parsed locale primary, followed by the non-duplicate device locales as fallback so
     * date/number formatting and resource fallback still behave naturally.
     */
    fun effectiveLocaleList(tag: String): LocaleList {
        val trimmed = tag.trim()
        val deviceLocales = Resources.getSystem().configuration.locales
        if (trimmed.isEmpty()) return deviceLocales
        val primary = Locale.forLanguageTag(trimmed)
        return if (deviceLocales.isEmpty) {
            LocaleList(primary)
        } else {
            val combined = ArrayList<Locale>(deviceLocales.size() + 1)
            combined.add(primary)
            for (i in 0 until deviceLocales.size()) {
                val l = deviceLocales[i]
                if (l != primary) combined.add(l)
            }
            LocaleList(*combined.toTypedArray())
        }
    }

    /**
     * A context whose `Resources` carry the effective locale list. `Configuration.setLocales(...)`
     * also writes the `SCREENLAYOUT_LAYOUTDIR` bits, so `ldrtl`-qualified drawables resolve for free.
     * Returns [base] unchanged when the effective list is empty (no locales to apply).
     */
    fun wrap(base: Context, tag: String): Context {
        val locales = effectiveLocaleList(tag)
        if (locales.isEmpty) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(locales)
        return base.createConfigurationContext(config)
    }

    /**
     * Applies the effective locale list to the process: `Locale.setDefault` and
     * `LocaleList.setDefault`. Needed for `java.text` / `java.time` / `String.format` and every
     * `Locale.getDefault()` reader — none of which Compose's locals touch. Not needed for
     * `DateFormat.getTimeFormat(ctx)`, which reads the context's own configuration.
     */
    fun applyGlobally(tag: String) {
        val locales = effectiveLocaleList(tag)
        if (locales.isEmpty) return
        Locale.setDefault(locales[0])
        LocaleList.setDefault(locales)
    }

    /**
     * Compose needs localized Resources, but it must not receive the bare ContextImpl returned by
     * createConfigurationContext(). That context is not an Activity: frame-rate matching, WebView
     * theming, and activity launches all depend on being able to unwrap the host Activity.
     *
     * The wrapper keeps the Activity as its base and delegates only resource/assets lookups to the
     * localized configuration context. Theme, window services, application context, and
     * startActivity semantics remain those of the host Activity.
     */
    fun wrapForCompose(base: Context, tag: String): Context {
        val localized = wrap(base, tag)
        val activity = findActivity(base) ?: return localized
        if (localized === base) return base
        return ActivityResourceContext(activity, localized)
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}

/** A localized resource view which still unwraps to the host Activity. */
private class ActivityResourceContext(
    host: Activity,
    private val localized: Context,
) : ContextWrapper(host) {
    override fun getResources(): Resources = localized.resources
    override fun getAssets() = localized.assets
    override fun getTheme() = baseContext.theme
}