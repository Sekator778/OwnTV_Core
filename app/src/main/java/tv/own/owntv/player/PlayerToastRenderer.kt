package tv.own.owntv.player

import android.content.Context
import androidx.annotation.StringRes
import tv.own.owntv.R
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore

/**
 * Resolves player toasts at display time, not when the process-wide player was constructed.
 * [OwnTVPlayer] is a singleton and can outlive an in-session locale switch, so every render uses a
 * fresh configuration context from the shared [LocaleStore]. Nested failures are rendered
 * recursively; `toString()` is never used as user-facing copy.
 */
class PlayerToastRenderer(
    private val baseContext: Context,
    private val localeStore: LocaleStore,
) {
    fun render(failure: PlaybackFailure): String {
        val context = AppLocale.wrap(baseContext, localeStore.currentTag.value)
        return failure.render(context)
    }

    fun text(@StringRes id: Int, vararg args: Any): String {
        val context = AppLocale.wrap(baseContext, localeStore.currentTag.value)
        return context.getString(id, *args)
    }

    /** Renders through [describe] so the HUD and this renderer can never drift apart on wording. */
    private fun PlaybackFailure.render(context: Context): String =
        describe { id, args -> context.getString(id, *args.toTypedArray()) }
}
