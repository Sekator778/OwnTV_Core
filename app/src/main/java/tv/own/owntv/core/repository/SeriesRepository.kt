package tv.own.owntv.core.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StalkerCredentials

/** Loads a series' seasons/episodes on demand (Xtream `get_series_info`; Stalker paged
 *  `get_ordered_list&movie_id=` season rows, plan D-2). Episodes carry their season number
 *  directly, so no separate Season rows are needed for browsing. */
class SeriesRepository(
    private val seriesDao: SeriesDao,
    private val sourceDao: SourceDao,
    private val xtream: XtreamClient,
    private val userData: tv.own.owntv.core.backup.UserDataResolver,
    private val stalkerClient: StalkerClient,
    private val stalkerAuth: StalkerAuthManager,
) {
    /** Returns true if episodes are available (cached or freshly fetched). Xtream + Stalker. */
    suspend fun loadEpisodes(series: SeriesEntity): Boolean = withContext(Dispatchers.IO) {
        if (seriesDao.episodeCount(series.id) > 0) return@withContext true
        val source = sourceDao.getById(series.sourceId) ?: return@withContext false
        val remoteId = series.remoteId
        if (remoteId.isNullOrBlank()) return@withContext false
        when (source.type) {
            SourceType.XTREAM -> loadXtreamEpisodes(series, source, remoteId)
            SourceType.STALKER -> loadStalkerEpisodes(series, source, remoteId)
            else -> false
        }
    }

    private suspend fun loadXtreamEpisodes(series: SeriesEntity, source: SourceEntity, remoteId: String): Boolean = try {
        val info = xtream.getSeriesInfo(source, remoteId)
        seriesDao.deleteEpisodes(series.id)
        val episodes = info.episodes.map { e ->
            EpisodeEntity(
                seriesId = series.id,
                seasonId = null,
                seasonNumber = e.seasonNumber,
                episodeNumber = e.episodeNumber,
                name = e.title,
                streamUrl = xtream.seriesEpisodeUrl(source, e.id, e.containerExt),
                containerExt = e.containerExt,
                remoteId = e.id,
            )
        }
        episodes.chunked(500).forEach { seriesDao.upsertEpisodes(it) }
        // Episode rows just appeared — restored episode history/resume can attach now.
        if (episodes.isNotEmpty()) runCatching { userData.resolvePending() }
        episodes.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /**
     * Stalker (plan D-2): the show's seasons come from paged `get_ordered_list&movie_id=<remoteId>`,
     * each row carrying a `series` array of episode numbers. Every episode stores the SEASON cmd as
     * its streamUrl (the item's identity); the playable per-episode URL is minted at play time via
     * `create_link&type=vod&series=<episode>` (StreamUrlResolver) — never at listing time, the links
     * are short-lived.
     */
    private suspend fun loadStalkerEpisodes(series: SeriesEntity, source: SourceEntity, remoteId: String): Boolean = try {
        val mac = source.mac?.let { StalkerClient.canonicalizeMac(it) } ?: return false
        val creds = StalkerCredentials(source.id, source.url, mac, source.userAgent)
        val seasons = ArrayList<StalkerClient.SeasonItem>()
        var page = 1
        while (page <= MAX_SEASON_PAGES) {
            val p = stalkerAuth.withAuthRetry(creds) { session ->
                stalkerClient.getSeriesSeasons(
                    session.apiBase, mac, session.token, creds.userAgent,
                    categoryId = null, movieId = remoteId, page = page,
                )
            }
            seasons += p.items
            val maxPer = p.maxPageItems.takeIf { it > 0 } ?: p.items.size
            val pages = if (maxPer > 0) (p.totalItems + maxPer - 1) / maxPer else 1
            if (p.items.isEmpty() || page >= pages) break
            page++
        }
        val episodes = ArrayList<EpisodeEntity>()
        seasons.forEachIndexed { index, season ->
            val cmd = season.cmd ?: return@forEachIndexed
            val seasonNo = season.seasonNumber ?: (index + 1)
            season.episodes.forEach { epNo ->
                episodes.add(
                    EpisodeEntity(
                        seriesId = series.id,
                        seasonId = null,
                        seasonNumber = seasonNo,
                        episodeNumber = epNo,
                        name = "Episode $epNo",
                        streamUrl = cmd, // season cmd — resolved per-episode at play time
                        containerExt = null,
                        remoteId = "${season.id}:$epNo",
                    )
                )
            }
        }
        android.util.Log.i(
            TAG,
            "stalker episodes seriesId=${series.id} remoteId=$remoteId seasons=${seasons.size} episodes=${episodes.size}",
        )
        seriesDao.deleteEpisodes(series.id)
        episodes.chunked(500).forEach { seriesDao.upsertEpisodes(it) }
        if (episodes.isNotEmpty()) runCatching { userData.resolvePending() }
        episodes.isNotEmpty()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "stalker episode load failed seriesId=${series.id}", e)
        false
    }

    private companion object {
        const val TAG = "SeriesRepository"

        /** Safety cap on season paging (a show never has hundreds of season pages). */
        const val MAX_SEASON_PAGES = 20
    }
}
