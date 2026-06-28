package tv.own.owntv.core.repository

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.ImportStage
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.SyncManager
import tv.own.owntv.core.sync.SyncResult

/**
 * Adds/links sources to a profile and runs imports. The setup wizard (Phase 6) and playlist screen
 * (Phase 13) drive this; the actual parsing/inserting lives in [SyncManager].
 */
class SourceRepository(
    private val sourceDao: SourceDao,
    private val syncManager: SyncManager,
    private val userData: tv.own.owntv.core.backup.UserDataResolver,
) {
    fun observeSources(profileId: Long): Flow<List<SourceEntity>> = sourceDao.observeForProfile(profileId)

    suspend fun getById(id: Long): SourceEntity? = sourceDao.getById(id)

    suspend fun addXtreamSource(
        profileId: Long, name: String, serverUrl: String, username: String, password: String,
        userAgent: String? = null, epgUrl: String? = null,
    ): SourceEntity = addAndLink(
        profileId,
        SourceEntity(name = name, type = SourceType.XTREAM, url = serverUrl, username = username, password = password, userAgent = userAgent, epgUrl = epgUrl),
    )

    suspend fun addM3uSource(
        profileId: Long, name: String, url: String, userAgent: String? = null, epgUrl: String? = null,
    ): SourceEntity = addAndLink(
        profileId,
        SourceEntity(name = name, type = SourceType.M3U, url = url, userAgent = userAgent, epgUrl = epgUrl),
    )

    private suspend fun addAndLink(profileId: Long, source: SourceEntity): SourceEntity {
        val id = sourceDao.insert(source)
        sourceDao.link(ProfileSourceCrossRef(profileId = profileId, sourceId = id))
        return source.copy(id = id)
    }

    suspend fun deleteSource(source: SourceEntity) = sourceDao.delete(source)

    suspend fun updateSource(source: SourceEntity) = sourceDao.update(source)

    suspend fun sync(source: SourceEntity, onProgress: (ImportStage) -> Unit, contentTypes: SyncContentTypes = SyncContentTypes()): SyncResult {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "sync wrapper start sourceId=${source.id} type=${source.type} contentTypes=$contentTypes")
        // Snapshot favorites/history/resume with stable keys BEFORE the sync clears content (their ids
        // change on every refresh, so they'd otherwise orphan — count badge set, list empty).
        val snapshotStartedAt = SystemClock.elapsedRealtime()
        val snapshot = runCatching { userData.exportForSource(source.id) }
            .onSuccess { Log.i(TAG, "userData export sourceId=${source.id} rows=${it.length()} ms=${SystemClock.elapsedRealtime() - snapshotStartedAt}") }
            .onFailure { Log.w(TAG, "userData export failed sourceId=${source.id} ms=${SystemClock.elapsedRealtime() - snapshotStartedAt}", it) }
            .getOrNull()
        val coreStartedAt = SystemClock.elapsedRealtime()
        val (result, _) = syncManager.sync(source, onProgress, contentTypes)
        Log.i(TAG, "core sync sourceId=${source.id} result=${result.name()} ms=${SystemClock.elapsedRealtime() - coreStartedAt}")
        if (result is SyncResult.Success) {
            // Content rows just regenerated — re-attach the snapshot (and any restored backup data) to
            // the new ids, and drop rows the provider removed.
            val relinkStartedAt = SystemClock.elapsedRealtime()
            runCatching { userData.relinkAfterSync(snapshot ?: org.json.JSONArray()) }
                .onSuccess { Log.i(TAG, "userData relink sourceId=${source.id} rows=${snapshot?.length() ?: 0} ms=${SystemClock.elapsedRealtime() - relinkStartedAt}") }
                .onFailure { Log.w(TAG, "userData relink failed sourceId=${source.id} ms=${SystemClock.elapsedRealtime() - relinkStartedAt}", it) }
        }
        Log.i(TAG, "sync wrapper end sourceId=${source.id} result=${result.name()} totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return result
    }

    private companion object {
        const val TAG = "SourceRepository"
    }
}

private fun SyncResult.name(): String = when (this) {
    is SyncResult.Success -> "Success"
    is SyncResult.Failed -> "Failed"
    SyncResult.Cancelled -> "Cancelled"
}
