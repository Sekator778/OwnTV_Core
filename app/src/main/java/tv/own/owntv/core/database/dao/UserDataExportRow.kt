package tv.own.owntv.core.database.dao

import tv.own.owntv.core.model.MediaType

data class UserDataExportRow(
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val sourceId: Long,
    val remoteId: String?,
    val name: String?,
    val seriesRemoteId: String?,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val at: Long,
    val positionMs: Long,
    val durationMs: Long,
)
