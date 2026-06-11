package tv.own.owntv.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * Phase 12 — backup & restore of the painful-to-re-enter setup: **profiles** (name/avatar/kids/PIN) and
 * **sources** (URLs + credentials + per-source UA) and their profile links, as a JSON file via SAF.
 * Content (channels/movies/series) is NOT backed up — it's large and re-syncs from the sources after
 * restore. Favorites/history reference volatile content ids, so they're left for a future schema.
 */
class BackupManager(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
) {
    /** Writes the backup into [folder] as owntv-backup.json; returns the file path. */
    suspend fun export(folder: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val profiles = profileDao.getAllOnce()
            val sources = sourceDao.getAllOnce()
            val links = sourceDao.allLinks()
            val root = JSONObject().apply {
                put("version", 1)
                put("profiles", JSONArray().apply { profiles.forEach { put(profileJson(it)) } })
                put("sources", JSONArray().apply { sources.forEach { put(sourceJson(it)) } })
                put("links", JSONArray().apply { links.forEach { put(JSONObject().put("profileId", it.profileId).put("sourceId", it.sourceId)) } })
            }
            if (!folder.exists()) folder.mkdirs()
            val out = File(folder, "owntv-backup.json")
            out.writeText(root.toString(2))
            out.absolutePath
        }
    }

    /** Replaces all profiles & sources with the file's contents, then activates the first profile. */
    suspend fun import(file: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(file.readText())
            val profiles = root.getJSONArray("profiles")
            val sources = root.getJSONArray("sources")
            val links = root.optJSONArray("links") ?: JSONArray()

            profileDao.deleteAll()       // cascades favorites/history/progress/profile_source
            sourceDao.deleteAllSources() // cascades content + profile_source

            for (i in 0 until profiles.length()) profileDao.insert(profileFrom(profiles.getJSONObject(i)))
            for (i in 0 until sources.length()) sourceDao.insert(sourceFrom(sources.getJSONObject(i)))
            for (i in 0 until links.length()) {
                val l = links.getJSONObject(i)
                sourceDao.link(ProfileSourceCrossRef(profileId = l.getLong("profileId"), sourceId = l.getLong("sourceId")))
            }
            profileDao.getAllOnce().firstOrNull()?.let { settings.setActiveProfile(it.id) }
            profiles.length() + sources.length()
        }
    }

    // --- mapping ---
    private fun profileJson(p: ProfileEntity) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("avatarColor", p.avatarColor); put("avatarId", p.avatarId)
        put("isKids", p.isKids); put("pinHash", p.pinHash ?: JSONObject.NULL); put("createdAt", p.createdAt)
    }

    private fun profileFrom(o: JSONObject) = ProfileEntity(
        id = o.getLong("id"), name = o.getString("name"), avatarColor = o.getInt("avatarColor"),
        avatarId = o.optInt("avatarId", 0), isKids = o.optBoolean("isKids", false),
        pinHash = o.optStringOrNull("pinHash"), createdAt = o.optLong("createdAt", System.currentTimeMillis()),
    )

    private fun sourceJson(s: SourceEntity) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("type", s.type.name); put("url", s.url)
        put("username", s.username ?: JSONObject.NULL); put("password", s.password ?: JSONObject.NULL)
        put("userAgent", s.userAgent ?: JSONObject.NULL); put("epgUrl", s.epgUrl ?: JSONObject.NULL)
        put("createdAt", s.createdAt); put("lastSyncAt", s.lastSyncAt ?: JSONObject.NULL)
    }

    private fun sourceFrom(o: JSONObject) = SourceEntity(
        id = o.getLong("id"), name = o.getString("name"),
        type = runCatching { SourceType.valueOf(o.getString("type")) }.getOrDefault(SourceType.M3U),
        url = o.getString("url"), username = o.optStringOrNull("username"), password = o.optStringOrNull("password"),
        userAgent = o.optStringOrNull("userAgent"), epgUrl = o.optStringOrNull("epgUrl"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        lastSyncAt = if (o.isNull("lastSyncAt")) null else o.optLong("lastSyncAt"),
    )
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
