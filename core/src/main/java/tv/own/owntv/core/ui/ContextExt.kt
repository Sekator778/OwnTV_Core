package tv.own.owntv.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * The [Activity] hosting this [Context], or null when there isn't one.
 *
 * A Compose `LocalContext` is usually a `ContextWrapper` chain rather than the Activity itself — the
 * theme wrapper, and in this app also the locale wrapper (`AppLocale`) — so anything that needs the real
 * Activity (display modes, window attributes, recreate) has to walk down to it.
 *
 * Consolidated from four byte-identical private copies (`FrameRateController`, `AutoFrameRatePrompt`,
 * `LocalizedContent`, `AppLocale`). The receiver is nullable because two of those call sites already had
 * a nullable context and would otherwise need a null check at every use.
 */
fun Context?.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
