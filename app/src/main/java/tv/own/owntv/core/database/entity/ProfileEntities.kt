package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.own.owntv.core.model.HlsSupport
import tv.own.owntv.core.model.SourceType

/**
 * A user profile (hybrid model). Owns its sources via [ProfileSourceCrossRef] and its own
 * favorites / history / progress (scoped by `profileId`). Kids profiles hide adult categories;
 * locked profiles store only a salted PIN hash.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatarColor: Int,
    /** Index into the [tv.own.owntv.ui.components.OwnTVAvatars] cartoon set. */
    val avatarId: Int = 0,
    val isKids: Boolean = false,
    val pinHash: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** [SourceEntity.livePrerollSecs] sentinel: use the global Settings value rather than a per-playlist one. */
const val FOLLOW_GLOBAL_PREROLL = -1

/** [SourceEntity.liveLatencyCustomSecs] sentinel: no per-playlist custom latency stored yet. */
const val FOLLOW_GLOBAL_LATENCY_SECS = -1

/** An IPTV source (M3U file/URL, Xtream account, or Stalker portal). Content rows reference their `sourceId`. */
@Entity(
    tableName = "sources",
    indices = [Index("type")],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /** Stalker portal MAC in canonical `AA:BB:CC:DD:EE:FF` form; null for M3U/Xtream. */
    val mac: String? = null,
    /** Optional Stalker/Ministra second-step device identity fields; null for other source types. */
    val stalkerSerialNumber: String? = null,
    val stalkerDeviceId: String? = null,
    val stalkerDeviceId2: String? = null,
    val stalkerSignature: String? = null,
    val userAgent: String? = null,
    /** XMLTV guide URL (M3U `url-tvg` or manually entered); Xtream EPG comes from the API. */
    val epgUrl: String? = null,
    /**
     * enabledScope (v17). `false` = never fetch AND never show this section for this source.
     * Cache + user data are retained — Off is a sync/visibility scope, not a deletion.
     */
    val syncLive: Boolean = true,
    val syncMovies: Boolean = true,
    val syncSeries: Boolean = true,
    /**
     * Whether the provider explicitly lists m3u8 in user_info.allowed_output_formats (v23), read at the
     * start of every Xtream sync. Detection hint only — it refines the playlist wording, it does NOT
     * gate [preferHls]: a panel that under-reports its formats must not stop the user asking for HLS.
     *
     * Tri-state ([HlsSupport]) since the answer is unknown until the first sync, and "unknown" must not
     * read as "unsupported". Stored in the same INTEGER column the old boolean used — see [HlsSupport].
     */
    val hlsSupported: HlsSupport = HlsSupport.UNKNOWN,
    /**
     * User preference (v23): prioritize .m3u8 streams over .ts for **Live TV only**. Catch-up is
     * deliberately excluded — the timeshift server serves recordings off disk with no HLS repackager
     * in front of it, so asking it for `.m3u8` breaks archives on accounts whose live TV is fine.
     */
    val preferHls: Boolean = false,
    /**
     * Per-playlist "Pre-buffer" override in seconds (v25). `-1` = follow the global
     * Settings value; `0` = off; `N` = fill N seconds before a live channel starts and after a
     * rebuffer. Per-playlist because the problem it solves is a *provider* that hiccups on a fixed
     * period — a user with one bad panel and three good ones should not have to slow all four down.
     */
    val livePrerollSecs: Int = FOLLOW_GLOBAL_PREROLL,
    /**
     * Per-playlist Live TV engine override (v34), as an [tv.own.owntv.player.EnginePreference] name.
     * `null` = follow the global Settings choice, which is what every existing row reads as.
     *
     * Per-playlist for the same reason as [livePrerollSecs]: which engine copes is a property of the
     * *provider's* stream format, so a user with one panel that only mpv can play should not have to
     * move all their playlists onto mpv. It sits **below** a per-channel pin and below the DRM rule —
     * see the resolution order in `LiveViewModel`.
     */
    val liveEnginePreference: String? = null,
    /**
     * Per-playlist Live latency override (v34), as a [tv.own.owntv.features.settings.data.LiveLatency]
     * name; `null` = follow the global setting. [liveLatencyCustomSecs] carries the seconds and is only
     * read when this is `CUSTOM`, mirroring the global pair exactly.
     */
    val liveLatencyMode: String? = null,
    val liveLatencyCustomSecs: Int = FOLLOW_GLOBAL_LATENCY_SECS,
    /**
     * How many simultaneous streams the provider allows (v27), from Xtream's
     * `user_info.max_connections`. `0` = unknown (M3U/Stalker, an older row, or a panel that doesn't
     * report it).
     *
     * `1` is the interesting value: the two playback engines must never overlap on such an account, and
     * until now the app only found that out by failing a tune and reading the 458 back. Knowing it at
     * sync time means the very first zap already behaves.
     */
    val maxConnections: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long? = null,
)

/** Many-to-many link letting a source be shared across profiles (hybrid model). */
@Entity(
    tableName = "profile_source",
    primaryKeys = ["profileId", "sourceId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId")],
)
data class ProfileSourceCrossRef(
    val profileId: Long,
    val sourceId: Long,
)
