package tv.own.owntv.core.model

/** What kind of media an item is. Used to scope favorites/history/progress generically. */
enum class MediaType { LIVE, MOVIE, SERIES, EPISODE }

/** How a source delivers its content. */
enum class SourceType { M3U, XTREAM, LOCAL_BACKUP, STALKER }

/**
 * What we know about an Xtream provider's HLS support, from `user_info.allowed_output_formats`.
 *
 * Three states rather than a boolean because "no" and "we haven't asked yet" are different answers and
 * the UI needs to tell them apart: a playlist that has never synced knows nothing, while one that has
 * synced and saw no `m3u8` in the list has a real negative answer worth showing.
 *
 * [code] is stored in `sources.hlsSupported` and is deliberately pinned: `0`/`1` keep the meaning the
 * old boolean column had, so every existing row reads back correctly with no migration — an old `1`
 * really was "supported", and an old `0` really was "either unsupported or never checked", which is
 * exactly [UNKNOWN]. The next sync resolves those rows to a definite answer.
 */
enum class HlsSupport(val code: Int) {
    UNKNOWN(0),
    SUPPORTED(1),
    UNSUPPORTED(2),
    ;

    companion object {
        fun fromCode(code: Int): HlsSupport = entries.firstOrNull { it.code == code } ?: UNKNOWN

        /** The panel answered: `m3u8` was either in `allowed_output_formats` or it wasn't. */
        fun of(supported: Boolean): HlsSupport = if (supported) SUPPORTED else UNSUPPORTED
    }
}

/** Lifecycle of a download (movies & series only). */
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }
