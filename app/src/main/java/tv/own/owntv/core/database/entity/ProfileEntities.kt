package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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
