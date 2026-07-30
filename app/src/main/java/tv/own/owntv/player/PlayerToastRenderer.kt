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

    private fun PlaybackFailure.render(context: Context): String = when (this) {
        PlaybackFailure.ImageSubtitleAudio -> context.getString(R.string.player_error_image_subtitle_audio)
        PlaybackFailure.ImageFormat -> context.getString(R.string.player_error_image_format)
        PlaybackFailure.ImageShow -> context.getString(R.string.player_error_image_show)
        PlaybackFailure.Channel -> context.getString(R.string.player_error_channel)
        PlaybackFailure.LostConnection -> context.getString(R.string.player_error_lost_connection)
        PlaybackFailure.StreamLink -> context.getString(R.string.player_error_stream_link)
        PlaybackFailure.NotStreaming -> context.getString(R.string.player_error_not_streaming)
        PlaybackFailure.AudioNoVideo -> context.getString(R.string.player_error_audio_no_video)
        PlaybackFailure.FileCorrupt -> context.getString(R.string.player_error_file_corrupt)
        PlaybackFailure.MultipleVideos -> context.getString(R.string.player_error_multiple_videos)
        PlaybackFailure.DecoderBusy -> context.getString(R.string.player_error_decoder_busy)
        PlaybackFailure.NoInternet -> context.getString(R.string.player_error_no_internet)
        PlaybackFailure.Surround -> context.getString(R.string.player_error_surround)
        PlaybackFailure.BothEnginesExoFirst -> context.getString(R.string.player_error_both_engines_exo_first)
        is PlaybackFailure.BothEnginesMpvFirst -> context.getString(
            R.string.player_error_both_engines_mpv_first,
            exoError.render(context),
        )
        is PlaybackFailure.ExoDecode -> context.getString(R.string.player_error_exo_decode, code)
        is PlaybackFailure.ExoPlay -> context.getString(R.string.player_error_exo_play, code)
        is PlaybackFailure.HardwareFallback -> context.getString(R.string.player_error_hardware_fallback, resolution)
        is PlaybackFailure.HardwareDisabled -> context.getString(R.string.player_error_hardware_disabled, resolution)
        is PlaybackFailure.StreamUnavailable -> context.getString(
            R.string.player_error_stream_unavailable,
            if (customUserAgentHint) context.getString(R.string.player_error_custom_user_agent) else "",
        )
        PlaybackFailure.MpvOpenDecode -> context.getString(R.string.player_error_mpv_open_decode)
        PlaybackFailure.MpvStreamNeverStarted -> context.getString(R.string.player_error_mpv_stream_never_started)
        is PlaybackFailure.Raw -> message
    }
}
