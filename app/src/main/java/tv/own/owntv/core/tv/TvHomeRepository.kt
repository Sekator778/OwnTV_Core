package tv.own.owntv.core.tv

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.R
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TvProviderProgramDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.TvProviderProgramEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.features.settings.data.SettingsRepository
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Mirrors the app's continue-watching state into Android TV provider rows. */
class TvHomeRepository(
    private val context: Context,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val progressDao: ProgressDao,
    private val tvProviderProgramDao: TvProviderProgramDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val channelHelper = PreviewChannelHelper(context)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "OwnTVHome"
        private const val WATCH_NEXT_MIN_POSITION_MS = 10_000L
        private const val WATCH_NEXT_COMPLETE_FRACTION = 0.95f
        private const val WATCH_NEXT_PUBLISH_INTERVAL_MS = 60_000L
        private const val RECENT_LIVE_MAX_ITEMS = 10
        private const val RECENT_LIVE_REFRESH_INTERVAL_MS = 5_000L
        private const val RECENT_LIVE_CHANNEL_GROUP_ID = 0L
        private const val RECENT_LIVE_CHANNEL_NAME = "Recent Live"
        private const val RECENT_LIVE_CHANNEL_STABLE_KEY = "recent-live"
    }

    private fun logD(message: String) {
        Log.d(TAG, message)
    }

    private fun logW(message: String, tr: Throwable? = null) {
        if (tr == null) Log.w(TAG, message) else Log.w(TAG, message, tr)
    }

    private fun TvProviderProgramEntity.describe(): String =
        "providerId=${providerProgramId ?: -1} surface=$surface mediaType=$mediaType groupId=$groupId targetItemId=$targetItemId lastPositionMs=$lastPositionMs durationMs=$durationMs lastPublishedAt=$lastPublishedAt"

    suspend fun refreshProfile(profileId: Long, allowBrowsableRequest: Boolean = false) = withContext(Dispatchers.IO) {
        if (profileId < 0) return@withContext
        val enabled = settings.androidTvHomeEnabled.first()
        logD("refreshProfile profile=$profileId enabled=$enabled allowBrowsable=$allowBrowsableRequest")
        if (!enabled) {
            logD("refreshProfile profile=$profileId clearing platform rows because Android TV home is disabled")
            clearProfile(profileId)
            return@withContext
        }
        mutex.withLock {
            logD("refreshProfile profile=$profileId publishing Watch Next and Recent Live rows")
            refreshWatchNextLocked(profileId)
            refreshRecentLiveLocked(profileId, allowBrowsableRequest)
        }
        logD("refreshProfile profile=$profileId done")
    }

    suspend fun clearProfile(profileId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rows = tvProviderProgramDao.getAllForProfile(profileId)
            logD("clearProfile profile=$profileId rows=${rows.size}")
            rows.forEach { deletePlatformRow(it) }
            tvProviderProgramDao.deleteForProfile(profileId)
        }
        logD("clearProfile profile=$profileId done")
    }

    suspend fun publishMovieProgress(profileId: Long, movieId: Long, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        val enabled = settings.androidTvHomeEnabled.first()
        if (profileId < 0 || !enabled) {
            logD("publishMovieProgress skip profile=$profileId enabled=$enabled movieId=$movieId positionMs=$positionMs durationMs=$durationMs")
            return@withContext
        }
        logD("publishMovieProgress profile=$profileId movieId=$movieId positionMs=$positionMs durationMs=$durationMs")
        mutex.withLock { syncMovie(profileId, movieId, positionMs, durationMs) }
    }

    suspend fun publishEpisodeProgress(profileId: Long, episodeId: Long, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        val enabled = settings.androidTvHomeEnabled.first()
        if (profileId < 0 || !enabled) {
            logD("publishEpisodeProgress skip profile=$profileId enabled=$enabled episodeId=$episodeId positionMs=$positionMs durationMs=$durationMs")
            return@withContext
        }
        logD("publishEpisodeProgress profile=$profileId episodeId=$episodeId positionMs=$positionMs durationMs=$durationMs")
        mutex.withLock { syncEpisode(profileId, episodeId, positionMs, durationMs) }
    }

    suspend fun refreshRecentLive(profileId: Long, allowBrowsableRequest: Boolean = false) = withContext(Dispatchers.IO) {
        val enabled = settings.androidTvHomeEnabled.first()
        if (profileId < 0 || !enabled) {
            logD("refreshRecentLive skip profile=$profileId enabled=$enabled allowBrowsable=$allowBrowsableRequest")
            return@withContext
        }
        logD("refreshRecentLive profile=$profileId allowBrowsable=$allowBrowsableRequest")
        mutex.withLock { refreshRecentLiveLocked(profileId, allowBrowsableRequest) }
    }

    private suspend fun refreshWatchNextLocked(profileId: Long) {
        val desired = buildDesiredRows(profileId)
        val desiredKeys = desired.map { it.key }.toSet()
        val existing = tvProviderProgramDao.getAllForProfile(profileId)
        logD("refreshWatchNext profile=$profileId desired=${desired.size} existing=${existing.size}")
        for (row in existing) {
            if (row.surface != TvProviderSurface.WATCH_NEXT) continue
            if (row.key() !in desiredKeys) {
                logD("refreshWatchNext deleting stale ${row.describe()}")
                deletePlatformRow(row)
                tvProviderProgramDao.delete(profileId, row.surface, row.mediaType, row.groupId)
            }
        }
        for (row in desired) {
            when (row) {
                is DesiredRow.Movie -> syncMovie(profileId, row.movieId, row.positionMs, row.durationMs, force = true)
                is DesiredRow.Episode -> syncEpisode(profileId, row.episodeId, row.positionMs, row.durationMs, force = true)
            }
        }
    }

    private suspend fun refreshRecentLiveLocked(profileId: Long, allowBrowsableRequest: Boolean) {
        val customizations = customize.observe(profileId, MediaType.LIVE).first()
        val channelRow = ensureRecentLiveChannel(profileId, allowBrowsableRequest)
        val channelId = channelRow.providerProgramId ?: return
        val now = System.currentTimeMillis()
        if (now - channelRow.lastPublishedAt < RECENT_LIVE_REFRESH_INTERVAL_MS) return

        val sourceIds = sourceDao.sourceIdsForProfile(profileId).toSet()
        val recentChannels = channelDao.recentlyWatched(profileId, RECENT_LIVE_MAX_ITEMS * 2).first()
            .filter { it.sourceId in sourceIds }
            .filter { !isHidden(customizations, it) }
            .distinctBy { it.id }
            .take(RECENT_LIVE_MAX_ITEMS)
        val existingRows = tvProviderProgramDao.getForSurface(profileId, TvProviderSurface.RECENT_LIVE)
            .filter { it.groupId != RECENT_LIVE_CHANNEL_GROUP_ID }
        logD(
            "refreshRecentLive profile=$profileId channelId=$channelId recent=${recentChannels.size} " +
                "sourceCount=${sourceIds.size} existing=${existingRows.size}",
        )

        val desiredKeys = recentChannels.map { liveStableKey(it) }.toSet()

        for (row in existingRows) {
            if (row.groupId !in desiredKeys) {
                deletePlatformRow(row)
                tvProviderProgramDao.delete(profileId, row.surface, row.mediaType, row.groupId)
            }
        }

        recentChannels.forEachIndexed { index, channel ->
            val stableKey = liveStableKey(channel)
            val row = tvProviderProgramDao.find(profileId, TvProviderSurface.RECENT_LIVE, MediaType.LIVE, stableKey)
                ?: TvProviderProgramEntity(
                    profileId = profileId,
                    surface = TvProviderSurface.RECENT_LIVE,
                    mediaType = MediaType.LIVE,
                    groupId = stableKey,
                    targetItemId = channel.id,
                )
            publishRecentLiveProgram(profileId, channelId, channel, row, index, customizations)
        }

        tvProviderProgramDao.upsert(channelRow.copy(lastPublishedAt = now, lastEngagementAt = now))
        logD("refreshRecentLive profile=$profileId updated channel bookkeeping")
    }

    suspend fun resolveLaunch(profileId: Long, deepLink: TvHomeDeepLink): TvHomeLaunch? = withContext(Dispatchers.IO) {
        val sourceIds = sourceDao.sourceIdsForProfile(profileId).toSet()
        if (sourceIds.isEmpty()) return@withContext null
        logD("resolveLaunch profile=$profileId type=${deepLink::class.simpleName} sourceCount=${sourceIds.size}")

        when (deepLink) {
            is TvHomeDeepLink.Movie -> {
                val movie = resolveMovie(deepLink) ?: return@withContext null
                if (movie.sourceId !in sourceIds) return@withContext null
                logD("resolveLaunch movie profile=$profileId movieId=${movie.id} sourceId=${movie.sourceId}")
                TvHomeLaunch.Movie(movie, progressDao.get(profileId, MediaType.MOVIE, movie.id)?.positionMs ?: 0L)
            }
            is TvHomeDeepLink.Live -> {
                val channel = resolveLiveChannel(deepLink) ?: return@withContext null
                if (channel.sourceId !in sourceIds) return@withContext null
                logD("resolveLaunch live profile=$profileId channelId=${channel.id} sourceId=${channel.sourceId}")
                TvHomeLaunch.Live(channel)
            }
            TvHomeDeepLink.OpenLiveSection -> null
            is TvHomeDeepLink.Episode -> {
                val show = resolveSeries(deepLink) ?: return@withContext null
                if (show.sourceId !in sourceIds) return@withContext null
                val episodes = orderedEpisodes(show.id)
                if (episodes.isEmpty()) return@withContext TvHomeLaunch.Series(show)

                val target = resolveEpisodeTarget(profileId, show.id, deepLink, episodes)
                    ?: return@withContext TvHomeLaunch.Series(show)
                val queue = episodes.filter { it.seasonNumber == target.seasonNumber }.sortedBy { it.episodeNumber }
                val startPosition = progressDao.get(profileId, MediaType.EPISODE, target.id)?.positionMs ?: 0L
                logD("resolveLaunch episode profile=$profileId showId=${show.id} episodeId=${target.id} queue=${queue.size} startPosition=$startPosition")
                TvHomeLaunch.Episode(show, target, queue.ifEmpty { listOf(target) }, startPosition)
            }
        }
    }

    private suspend fun buildDesiredRows(profileId: Long): List<DesiredRow> {
        val out = mutableListOf<DesiredRow>()
        val allProgress = progressDao.getAllOnce().filter { it.profileId == profileId }

        for (progress in allProgress) {
            if (progress.mediaType != MediaType.MOVIE) continue
            val movie = movieDao.getById(progress.itemId) ?: continue
            if (!isVisibleToProfile(profileId, movie.sourceId)) continue
            if (!eligibleForWatchNext(progress.positionMs, progress.durationMs)) continue
            out += DesiredRow.Movie(movieStableKeyHash(movie), movie.id, progress.positionMs, progress.durationMs)
        }

        val latestBySeries = LinkedHashMap<Long, PlaybackProgressEntity>()
        for (progress in allProgress) {
            if (progress.mediaType != MediaType.EPISODE) continue
            val episode = seriesDao.getEpisodeById(progress.itemId) ?: continue
            val current = latestBySeries[episode.seriesId]
            if (current == null || progress.updatedAt >= current.updatedAt) {
                latestBySeries[episode.seriesId] = progress
            }
        }
        for (progress in latestBySeries.values) {
            val episode = seriesDao.getEpisodeById(progress.itemId) ?: continue
            val show = seriesDao.getSeriesById(episode.seriesId) ?: continue
            if (!isVisibleToProfile(profileId, show.sourceId)) continue
            val episodes = orderedEpisodes(show.id)
            val currentIndex = episodes.indexOfFirst { it.id == episode.id }
            val isComplete = isCompleted(progress)
            if (!isComplete && !eligibleForWatchNext(progress.positionMs, progress.durationMs)) continue
            if (isComplete && currentIndex == episodes.lastIndex) continue
            out += DesiredRow.Episode(episodeStableKeyHash(show, episode), show.id, episode.id, progress.positionMs, progress.durationMs)
        }

        return out
    }

    private suspend fun syncMovie(profileId: Long, movieId: Long, positionMs: Long, durationMs: Long, force: Boolean = false) {
        val movie = movieDao.getById(movieId) ?: return
        if (!isVisibleToProfile(profileId, movie.sourceId)) {
            logD("syncMovie skip hidden profile=$profileId movieId=$movieId sourceId=${movie.sourceId}")
            return
        }

        val groupId = movieStableKeyHash(movie)
        val existing = tvProviderProgramDao.find(profileId, TvProviderSurface.WATCH_NEXT, MediaType.MOVIE, groupId)
        val eligible = eligibleForWatchNext(positionMs, durationMs)
        logD(
            "syncMovie profile=$profileId movieId=$movieId groupId=$groupId existing=${existing?.providerProgramId ?: -1} " +
                "eligible=$eligible force=$force",
        )

        if (!eligible) {
            logD("syncMovie delete profile=$profileId movieId=$movieId groupId=$groupId reason=ineligible")
            deleteExisting(profileId, existing, MediaType.MOVIE, groupId)
            return
        }

        val row = persistableRow(
            existing = existing,
            profileId = profileId,
            mediaType = MediaType.MOVIE,
            groupId = groupId,
            targetItemId = movie.id,
            positionMs = positionMs,
            durationMs = durationMs,
        )
        if (!force && !shouldPublish(existing, row.targetItemId, positionMs, durationMs)) return
        upsertWatchNext(movie, row)
    }

    private suspend fun syncEpisode(profileId: Long, episodeId: Long, positionMs: Long, durationMs: Long, force: Boolean = false) {
        val episode = seriesDao.getEpisodeById(episodeId) ?: return
        val show = seriesDao.getSeriesById(episode.seriesId) ?: return
        if (!isVisibleToProfile(profileId, show.sourceId)) {
            logD("syncEpisode skip hidden profile=$profileId episodeId=$episodeId showId=${show.id} sourceId=${show.sourceId}")
            return
        }

        val episodes = orderedEpisodes(show.id)
        val currentIndex = episodes.indexOfFirst { it.id == episode.id }
        val isComplete = isCompleted(positionMs, durationMs)
        val currentGroupId = episodeStableKeyHash(show, episode)
        val existingCurrent = tvProviderProgramDao.find(profileId, TvProviderSurface.WATCH_NEXT, MediaType.EPISODE, currentGroupId)
        val eligible = eligibleForWatchNext(positionMs, durationMs)
        logD(
            "syncEpisode profile=$profileId episodeId=$episodeId showId=${show.id} groupId=$currentGroupId existing=${existingCurrent?.providerProgramId ?: -1} " +
                "eligible=$eligible complete=$isComplete currentIndex=$currentIndex force=$force",
        )
        if (!isComplete && !eligible) {
            logD("syncEpisode delete profile=$profileId episodeId=$episodeId groupId=$currentGroupId reason=ineligible")
            deleteExisting(profileId, existingCurrent, MediaType.EPISODE, currentGroupId)
            return
        }
        if (isComplete && currentIndex == episodes.lastIndex) {
            logD("syncEpisode delete profile=$profileId episodeId=$episodeId groupId=$currentGroupId reason=show-complete-last-episode")
            deleteExisting(profileId, existingCurrent, MediaType.EPISODE, currentGroupId)
            return
        }
        val targetCandidate: EpisodeEntity? = if (isComplete && currentIndex >= 0) {
            episodes.getOrNull(currentIndex + 1)
        } else {
            episode
        }
        val target = targetCandidate ?: run {
            deleteExisting(profileId, existingCurrent, MediaType.EPISODE, currentGroupId)
            return
        }

        val row = persistableRow(
            existing = existingCurrent,
            profileId = profileId,
            mediaType = MediaType.EPISODE,
            groupId = currentGroupId,
            targetItemId = target.id,
            positionMs = if (isComplete) 0L else positionMs,
            durationMs = if (isComplete) (target.durationSecs?.times(1000L) ?: durationMs) else durationMs,
        )
        if (!force && !shouldPublish(existingCurrent, row.targetItemId, positionMs, durationMs)) return
        val watchNextType = if (isComplete && target.id != episode.id) {
            TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
        } else {
            TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
        }
        logD(
            "syncEpisode publish profile=$profileId episodeId=$episodeId targetId=${target.id} watchNextType=$watchNextType " +
                "row=${row.describe()}",
        )
        upsertWatchNext(show, target, row, watchNextType, episodeStableKey(show, episode))
    }

    private suspend fun upsertWatchNext(movie: MovieEntity, row: TvProviderProgramEntity) {
        val stableKey = movieStableKey(movie)
        val program = WatchNextProgram.Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setType(TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
            .setTitle(movie.name)
            .setDescription(movie.year?.toString() ?: "Movie")
            .setPosterArtUri(safeMediaArtUri(movie.posterUrl))
            .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_MOVIE_POSTER)
            .setLastPlaybackPositionMillis(safeMillisToInt(row.lastPositionMs))
            .setDurationMillis(safeMillisToInt(row.durationMs))
            .setInternalProviderId(platformInternalId(TvProviderSurface.WATCH_NEXT, row.profileId, MediaType.MOVIE, stableKey))
            .setIntent(Intent(Intent.ACTION_VIEW, TvHomeDeepLink.Movie(movie.sourceId, movie.remoteId, movie.name).toUri()))
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .build()
        logD("persistWatchNext movie profile=${row.profileId} stableKey=$stableKey row=${row.describe()}")
        persistProgram(row, program)
    }

    private suspend fun upsertWatchNext(
        show: SeriesEntity,
        episode: EpisodeEntity,
        row: TvProviderProgramEntity,
        watchNextType: Int,
        rowStableKey: String,
    ) {
        val program = WatchNextProgram.Builder()
            .setWatchNextType(watchNextType)
            .setType(TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE)
            .setTitle(episode.name.ifBlank { show.name })
            .setDescription(buildList {
                add(show.name)
                add("Season ${episode.seasonNumber}")
                if (episode.episodeNumber > 0) add("Episode ${episode.episodeNumber}")
            }.joinToString(" · "))
            .setPosterArtUri(safeMediaArtUri(show.posterUrl))
            .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_MOVIE_POSTER)
            .setLastPlaybackPositionMillis(safeMillisToInt(row.lastPositionMs))
            .setDurationMillis(safeMillisToInt(row.durationMs))
            .setInternalProviderId(platformInternalId(TvProviderSurface.WATCH_NEXT, row.profileId, MediaType.EPISODE, rowStableKey))
            .setIntent(
                Intent(
                    Intent.ACTION_VIEW,
                    TvHomeDeepLink.Episode(
                        seriesSourceId = show.sourceId,
                        seriesRemoteId = show.remoteId,
                        seriesName = show.name,
                        episodeRemoteId = episode.remoteId,
                        season = episode.seasonNumber,
                        episode = episode.episodeNumber,
                    ).toUri(),
                ),
            )
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .build()
        logD("persistWatchNext episode profile=${row.profileId} row=${row.describe()} watchNextType=$watchNextType")
        persistProgram(row, program)
    }

    private suspend fun persistProgram(row: TvProviderProgramEntity, program: WatchNextProgram) {
        val programId = row.providerProgramId
        if (programId != null) {
            val updated = runCatching {
                resolver.update(TvContractCompat.buildWatchNextProgramUri(programId), program.toContentValues(), null, null)
            }.onFailure { t ->
                logW("persistWatchNext update failed profile=${row.profileId} programId=$programId row=${row.describe()}", t)
            }.getOrNull() ?: return
            logD("persistWatchNext update profile=${row.profileId} programId=$programId updated=$updated row=${row.describe()}")
            if (updated == 0) {
                insertProgram(row, program)
            } else {
                tvProviderProgramDao.upsert(row.copy(lastPublishedAt = System.currentTimeMillis(), lastEngagementAt = System.currentTimeMillis()))
            }
        } else {
            insertProgram(row, program)
        }
    }

    private suspend fun insertProgram(row: TvProviderProgramEntity, program: WatchNextProgram) {
        val uri = runCatching { resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues()) }
            .onFailure { t ->
                logW("persistWatchNext insert failed profile=${row.profileId} row=${row.describe()}", t)
            }
            .getOrNull()
        if (uri == null) {
            logW("persistWatchNext insert returned null profile=${row.profileId} row=${row.describe()}")
            return
        }
        val providerId = ContentUris.parseId(uri)
        logD("persistWatchNext insert profile=${row.profileId} programId=$providerId row=${row.describe()}")
        tvProviderProgramDao.upsert(
            row.copy(
                providerProgramId = providerId,
                lastPublishedAt = System.currentTimeMillis(),
                lastEngagementAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun deleteExisting(profileId: Long, existing: TvProviderProgramEntity?, mediaType: MediaType, groupId: Long) {
        existing?.let { deletePlatformRow(it) }
        tvProviderProgramDao.delete(profileId, TvProviderSurface.WATCH_NEXT, mediaType, groupId)
    }

    private suspend fun deletePlatformRow(row: TvProviderProgramEntity) {
        row.providerProgramId?.let {
            runCatching {
                when (row.surface) {
                    TvProviderSurface.WATCH_NEXT -> resolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null)
                    TvProviderSurface.RECENT_LIVE -> {
                        if (row.groupId == RECENT_LIVE_CHANNEL_GROUP_ID) channelHelper.deletePreviewChannel(it)
                        else channelHelper.deletePreviewProgram(it)
                    }
                }
            }
        }
    }

    private suspend fun ensureRecentLiveChannel(profileId: Long, allowBrowsableRequest: Boolean): TvProviderProgramEntity {
        val existing = tvProviderProgramDao.find(profileId, TvProviderSurface.RECENT_LIVE, MediaType.LIVE, RECENT_LIVE_CHANNEL_GROUP_ID)
        val now = System.currentTimeMillis()
        if (existing?.providerProgramId != null) {
            val current = runCatching { channelHelper.getPreviewChannel(existing.providerProgramId) }
                .getOrElse { t ->
                    logW("ensureRecentLiveChannel read failed profile=$profileId channelId=${existing.providerProgramId}", t)
                    null
                }
            if (current != null) {
                val desired = buildRecentLiveChannel(profileId)
                if (current.hasAnyUpdatedValues(desired)) {
                    logD("ensureRecentLiveChannel update profile=$profileId channelId=${existing.providerProgramId} allowBrowsable=$allowBrowsableRequest")
                    runCatching { channelHelper.updatePreviewChannel(existing.providerProgramId, desired) }
                }
                if (allowBrowsableRequest && !current.isBrowsable()) {
                    logD("ensureRecentLiveChannel requestBrowsable profile=$profileId channelId=${existing.providerProgramId}")
                    runCatching { TvContractCompat.requestChannelBrowsable(context, existing.providerProgramId) }
                }
                logD("ensureRecentLiveChannel reuse profile=$profileId channelId=${existing.providerProgramId}")
                return existing
            }
            logW("ensureRecentLiveChannel existing row missing from platform profile=$profileId providerId=${existing.providerProgramId}")
        }

        val channel = buildRecentLiveChannel(profileId)
        val ownChannelCount = runCatching { channelHelper.getAllChannels().count { it.packageName == context.packageName } }
            .getOrDefault(0)
        val channelId = runCatching {
            if (ownChannelCount == 0) channelHelper.publishDefaultChannel(channel)
            else {
                val published = channelHelper.publishChannel(channel)
                if (allowBrowsableRequest && published > 0) {
                    runCatching { TvContractCompat.requestChannelBrowsable(context, published) }
                }
                published
            }
        }.getOrElse { -1L }
        logD("ensureRecentLiveChannel publish profile=$profileId ownChannelCount=$ownChannelCount allowBrowsable=$allowBrowsableRequest result=$channelId")
        if (channelId <= 0L) {
            logW("ensureRecentLiveChannel publish failed profile=$profileId result=$channelId")
            return existing ?: TvProviderProgramEntity(
                profileId = profileId,
                surface = TvProviderSurface.RECENT_LIVE,
                mediaType = MediaType.LIVE,
                groupId = RECENT_LIVE_CHANNEL_GROUP_ID,
                targetItemId = 0L,
                providerProgramId = null,
                lastPublishedAt = 0L,
            )
        }

        val row = (existing ?: TvProviderProgramEntity(
            profileId = profileId,
            surface = TvProviderSurface.RECENT_LIVE,
            mediaType = MediaType.LIVE,
            groupId = RECENT_LIVE_CHANNEL_GROUP_ID,
            targetItemId = channelId,
            providerProgramId = channelId,
        )).copy(
            profileId = profileId,
            surface = TvProviderSurface.RECENT_LIVE,
            mediaType = MediaType.LIVE,
            groupId = RECENT_LIVE_CHANNEL_GROUP_ID,
            targetItemId = channelId,
            providerProgramId = channelId,
            lastEngagementAt = now,
            lastPublishedAt = 0L,
        )
        logD("ensureRecentLiveChannel stored profile=$profileId channelId=$channelId row=${row.describe()}")
        tvProviderProgramDao.upsert(row)
        return row
    }

    private suspend fun publishRecentLiveProgram(
        profileId: Long,
        channelId: Long,
        channel: tv.own.owntv.core.database.entity.ChannelEntity,
        row: TvProviderProgramEntity,
        index: Int,
        customizations: SectionCustomizations,
    ) {
        val label = customizations.itemNames[CustomizeKeys.channel(channel)] ?: channel.name
        val art = safeLiveArtUri(channel.logoUrl)
        val stableKey = liveStableKey(channel)
        val stableKeyString = liveStableKeyString(channel)
        val program = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewProgramColumns.TYPE_CHANNEL)
            .setTitle(label)
            .setDescription(channelDaoName(channel))
            .setInternalProviderId(platformInternalId(TvProviderSurface.RECENT_LIVE, profileId, MediaType.LIVE, stableKeyString))
            .setIntent(Intent(Intent.ACTION_VIEW, TvHomeDeepLink.Live(channel.sourceId, channel.remoteId, channel.name).toUri()))
            .setWeight(RECENT_LIVE_MAX_ITEMS - index)
            .apply { if (art != null) setPosterArtUri(art) }
            .apply { if (art != null) setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9) }
            .build()
        logD("persistRecentLive profile=$profileId channelId=$channelId index=$index label=$label row=${row.describe()}")
        persistRecentLiveProgram(row, program, stableKey)
    }

    private suspend fun persistRecentLiveProgram(row: TvProviderProgramEntity, program: PreviewProgram, stableKey: Long) {
        val existing = row.providerProgramId
        if (existing != null) {
            val updated = runCatching {
                resolver.update(TvContractCompat.buildPreviewProgramUri(existing), program.toContentValues(), null, null)
            }.onFailure { t ->
                logW("persistRecentLive update failed profile=${row.profileId} programId=$existing row=${row.describe()}", t)
            }.getOrNull() ?: return
            logD("persistRecentLive update profile=${row.profileId} programId=$existing updated=$updated row=${row.describe()}")
            if (updated == 0) {
                insertRecentLiveProgram(row, program, stableKey)
            } else {
                tvProviderProgramDao.upsert(row.copy(lastPublishedAt = System.currentTimeMillis(), lastEngagementAt = System.currentTimeMillis(), groupId = stableKey))
            }
        } else {
            insertRecentLiveProgram(row, program, stableKey)
        }
    }

    private suspend fun insertRecentLiveProgram(row: TvProviderProgramEntity, program: PreviewProgram, stableKey: Long) {
        val uri = runCatching { resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues()) }
            .onFailure { t ->
                logW("persistRecentLive insert failed profile=${row.profileId} row=${row.describe()}", t)
            }
            .getOrNull()
        if (uri == null) {
            logW("persistRecentLive insert returned null profile=${row.profileId} row=${row.describe()}")
            return
        }
        val providerId = ContentUris.parseId(uri)
        logD("persistRecentLive insert profile=${row.profileId} programId=$providerId row=${row.describe()}")
        tvProviderProgramDao.upsert(
            row.copy(
                providerProgramId = providerId,
                groupId = stableKey,
                lastPublishedAt = System.currentTimeMillis(),
                lastEngagementAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun isVisibleToProfile(profileId: Long, sourceId: Long): Boolean =
        sourceDao.sourceIdsForProfile(profileId).contains(sourceId)

    private suspend fun orderedEpisodes(seriesId: Long): List<EpisodeEntity> =
        seriesDao.episodesBySeries(seriesId).first()
            .sortedWith(compareBy<EpisodeEntity> { it.seasonNumber }.thenBy { it.episodeNumber })

    private fun buildRecentLiveChannel(profileId: Long): PreviewChannel {
        return PreviewChannel.Builder()
            .setDisplayName(RECENT_LIVE_CHANNEL_NAME)
            .setDescription("Recently watched live channels")
            .setAppLinkIntentUri(TvHomeDeepLink.OpenLiveSection.toUri())
            .setInternalProviderId(platformInternalId(TvProviderSurface.RECENT_LIVE, profileId, MediaType.LIVE, RECENT_LIVE_CHANNEL_STABLE_KEY))
            .setLogo(resourceUri(R.drawable.tv_banner))
            .build()
    }

    private fun movieStableKey(movie: MovieEntity): String =
        // Best-effort identity: remoteId is preferred, name is a fallback for providers that do not
        // expose stable IDs. A rename can still force a new key until the next refresh.
        "movie:${movie.sourceId}:${movie.remoteId ?: movie.name}"

    private fun episodeStableKey(show: SeriesEntity, episode: EpisodeEntity): String =
        // Best-effort identity for series/episodes: remote IDs win; name fallback is only for feeds
        // that do not expose stable IDs.
        "episode:${show.sourceId}:${show.remoteId ?: show.name}:${episode.remoteId ?: "${episode.seasonNumber}-${episode.episodeNumber}"}"

    private fun liveStableKeyString(channel: tv.own.owntv.core.database.entity.ChannelEntity): String =
        // Best-effort identity for live channels; remote IDs are preferred when the provider has them.
        "live:${channel.sourceId}:${channel.remoteId ?: channel.name}"

    private fun movieStableKeyHash(movie: MovieEntity): Long = stableHash64(movieStableKey(movie))

    private fun episodeStableKeyHash(show: SeriesEntity, episode: EpisodeEntity): Long =
        stableHash64(episodeStableKey(show, episode))

    private fun safeMillisToInt(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun safeLiveArtUri(raw: String?): Uri? {
        val value = raw?.trim().orEmpty()
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value.toUri()
            else -> null
        }
    }

    private fun safeMediaArtUri(raw: String?): Uri? = safeLiveArtUri(raw)

    private fun resourceUri(resId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resId")

    private fun liveStableKey(channel: tv.own.owntv.core.database.entity.ChannelEntity): Long =
        stableHash64(liveStableKeyString(channel))

    private fun isHidden(customizations: SectionCustomizations, channel: tv.own.owntv.core.database.entity.ChannelEntity): Boolean =
        CustomizeKeys.channel(channel) in customizations.hiddenItems

    private fun channelDaoName(channel: tv.own.owntv.core.database.entity.ChannelEntity): String = channel.name

    private suspend fun resolveMovie(deepLink: TvHomeDeepLink.Movie): MovieEntity? {
        val sourceId = deepLink.sourceId
        val remoteId = deepLink.remoteId
        val name = deepLink.name
        val movie = when {
            sourceId != null && !remoteId.isNullOrBlank() -> movieDao.findByRemote(sourceId, remoteId)
            sourceId != null && !name.isNullOrBlank() -> movieDao.findByName(sourceId, name)
            deepLink.itemId != null -> movieDao.getById(deepLink.itemId)
            else -> null
        } ?: return null
        if (sourceId != null && movie.sourceId != sourceId) return null
        return movie
    }

    private suspend fun resolveLiveChannel(deepLink: TvHomeDeepLink.Live): tv.own.owntv.core.database.entity.ChannelEntity? {
        val sourceId = deepLink.sourceId
        val remoteId = deepLink.remoteId
        val name = deepLink.name
        val channel = when {
            sourceId != null && !remoteId.isNullOrBlank() -> channelDao.findByRemote(sourceId, remoteId)
            sourceId != null && !name.isNullOrBlank() -> channelDao.findByName(sourceId, name)
            deepLink.itemId != null -> channelDao.getById(deepLink.itemId)
            else -> null
        } ?: return null
        if (sourceId != null && channel.sourceId != sourceId) return null
        return channel
    }

    private suspend fun resolveSeries(deepLink: TvHomeDeepLink.Episode): SeriesEntity? {
        val sourceId = deepLink.seriesSourceId
        val remoteId = deepLink.seriesRemoteId
        val name = deepLink.seriesName
        val show = when {
            sourceId != null && !remoteId.isNullOrBlank() -> seriesDao.findSeriesByRemote(sourceId, remoteId)
            sourceId != null && !name.isNullOrBlank() -> seriesDao.findSeriesByName(sourceId, name)
            deepLink.seriesItemId != null -> seriesDao.getSeriesById(deepLink.seriesItemId)
            else -> null
        } ?: return null
        if (sourceId != null && show.sourceId != sourceId) return null
        return show
    }

    private suspend fun resolveEpisodeTarget(
        profileId: Long,
        seriesId: Long,
        deepLink: TvHomeDeepLink.Episode,
        episodes: List<EpisodeEntity>,
    ): EpisodeEntity? {
        val season = deepLink.season
        val episodeNo = deepLink.episode
        val exact = when {
            !deepLink.episodeRemoteId.isNullOrBlank() -> seriesDao.findEpisodeByRemote(seriesId, deepLink.episodeRemoteId)
            season != null && episodeNo != null -> seriesDao.findEpisodeByNumber(seriesId, season, episodeNo)
            deepLink.episodeItemId != null -> seriesDao.getEpisodeById(deepLink.episodeItemId)
            else -> null
        }
        if (exact != null) return exact

        val fallbackStartIndex = when {
            season != null && episodeNo != null ->
                episodes.indexOfFirst { candidate ->
                    candidate.seasonNumber > season ||
                        (candidate.seasonNumber == season && candidate.episodeNumber > episodeNo)
                }.takeIf { it >= 0 } ?: 0
            else -> 0
        }

        if (fallbackStartIndex == 0) {
            logD("resolveEpisodeTarget fallback from start profile=$profileId seriesId=$seriesId episodeCount=${episodes.size}")
        }

        val candidates = if (fallbackStartIndex == 0) episodes else episodes.drop(fallbackStartIndex)
        return candidates.firstOrNull { episode ->
            val progress = progressDao.get(profileId, MediaType.EPISODE, episode.id)
            progress == null || !isCompleted(progress)
        } ?: if (fallbackStartIndex == 0) null else episodes.firstOrNull { episode ->
            val progress = progressDao.get(profileId, MediaType.EPISODE, episode.id)
            progress == null || !isCompleted(progress)
        }
    }

    private fun platformInternalId(surface: TvProviderSurface, profileId: Long, mediaType: MediaType, stableKey: String): String =
        "owntv:${surface.name.lowercase()}:${sha256Hex("$profileId|${mediaType.name}|$stableKey")}"

    private fun stableHash64(value: String): Long = ByteBuffer.wrap(sha256Bytes(value)).long

    private fun sha256Hex(value: String): String = sha256Bytes(value).joinToString("") { "%02x".format(it) }

    private fun sha256Bytes(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun eligibleForWatchNext(positionMs: Long, durationMs: Long): Boolean =
        positionMs >= WATCH_NEXT_MIN_POSITION_MS && durationMs > 0 && !isCompleted(positionMs, durationMs)

    private fun isCompleted(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= (durationMs * WATCH_NEXT_COMPLETE_FRACTION).toLong()

    private fun isCompleted(progress: PlaybackProgressEntity): Boolean = isCompleted(progress.positionMs, progress.durationMs)

    private fun shouldPublish(
        existing: TvProviderProgramEntity?,
        targetItemId: Long,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (existing == null || existing.providerProgramId == null) return true
        if (existing.targetItemId != targetItemId) return true
        if (isCompleted(positionMs, durationMs)) return true
        return now - existing.lastPublishedAt >= WATCH_NEXT_PUBLISH_INTERVAL_MS
    }

    private fun persistableRow(
        existing: TvProviderProgramEntity?,
        profileId: Long,
        mediaType: MediaType,
        groupId: Long,
        targetItemId: Long,
        positionMs: Long,
        durationMs: Long,
    ): TvProviderProgramEntity = (existing ?: TvProviderProgramEntity(
        profileId = profileId,
        surface = TvProviderSurface.WATCH_NEXT,
        mediaType = mediaType,
        groupId = groupId,
        targetItemId = targetItemId,
    )).copy(
        profileId = profileId,
        surface = TvProviderSurface.WATCH_NEXT,
        mediaType = mediaType,
        groupId = groupId,
        targetItemId = targetItemId,
        lastPositionMs = positionMs,
        durationMs = durationMs,
        lastEngagementAt = System.currentTimeMillis(),
        lastPublishedAt = System.currentTimeMillis(),
    )

    private fun TvProviderProgramEntity.key(): String = "${surface.name}:${mediaType.name}:$groupId"

    private sealed interface DesiredRow {
        val key: String

        data class Movie(val groupId: Long, val movieId: Long, val positionMs: Long, val durationMs: Long) : DesiredRow {
            override val key: String = "${TvProviderSurface.WATCH_NEXT.name}:${MediaType.MOVIE.name}:$groupId"
        }

        data class Episode(val groupId: Long, val showId: Long, val episodeId: Long, val positionMs: Long, val durationMs: Long) : DesiredRow {
            override val key: String = "${TvProviderSurface.WATCH_NEXT.name}:${MediaType.EPISODE.name}:$groupId"
        }
    }
}
