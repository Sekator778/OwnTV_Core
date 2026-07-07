package tv.own.owntv.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * Phase 12 — backup & restore of the painful-to-re-enter setup: **profiles** (name/avatar/kids/PIN),
 * **sources** (URLs + credentials + per-source UA) and their profile links, plus per-profile
 * **customizations** (hidden/renamed/reordered categories & channels), favorites, watch history and
 * resume positions — as a JSON file. The user picks which [Section]s to include on export and which
 * to apply on restore. Content (channels/movies/series) is NOT backed up — it's large and re-syncs
 * from the sources after restore. Profile/source ids are preserved on restore, so customization keys
 * stay valid.
 */
class BackupManager(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val userData: UserDataResolver,
    private val epgSources: tv.own.owntv.core.epg.EpgSourceStore,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
    private val forceMpvStore: tv.own.owntv.core.player.ForceMpvStore,
    private val vodEngineStore: tv.own.owntv.core.player.VodEngineStore,
) {
    /** What a backup can contain; the user multi-selects these for export and restore. */
    enum class Section(val label: String, val desc: String) {
        SOURCES("Profiles & sources", "Viewers, PINs, playlists, EPG feeds and credentials"),
        CUSTOMIZE("Customizations", "Hidden/renamed/reordered categories, channels & EPG matches"),
        FAVORITES("Favorites", "Starred channels, movies and series"),
        HISTORY("Watch history", "Recently watched lists"),
        RESUME("Resume positions", "Where you stopped in movies & episodes"),
        SETTINGS("App settings", "Theme, accent, player & layout preferences"),
    }

    /**
     * Writes the chosen [sections] into [folder] as owntv-backup.json; returns the file path.
     *
     * Secret fields (source passwords, proxy password) are NEVER written as plaintext. When
     * [backupPassword] is a non-blank passphrase, they are encrypted field-by-field (AES-GCM) and a
     * root `crypto` block records the KDF params. When it is null/blank, secrets are simply omitted —
     * the caller is expected to have warned the user that passwords must be re-entered after restore.
     */
    suspend fun export(
        folder: File,
        sections: Set<Section> = Section.entries.toSet(),
        backupPassword: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // Set up field encryption only if a passphrase was provided.
            val pass = backupPassword?.takeIf { it.isNotBlank() }
            val salt = if (pass != null) BackupCrypto.newSalt() else null
            val key = if (pass != null && salt != null) BackupCrypto.deriveKey(pass.toCharArray(), salt, BackupCrypto.ITERATIONS) else null
            val seal: ((String) -> JSONObject)? = key?.let { k -> { plain -> BackupCrypto.encrypt(k, plain) } }

            val root = JSONObject().apply {
                put("version", 7) // v7: auto-refresh maps + default source (SOURCES); compat-mode pins (SETTINGS)
                put("sections", JSONArray().apply { sections.forEach { put(it.name) } })
                if (salt != null) put("crypto", BackupCrypto.cryptoBlock(salt))
                if (Section.SOURCES in sections) {
                    put("profiles", JSONArray().apply { profileDao.getAllOnce().forEach { put(profileJson(it)) } })
                    put("sources", JSONArray().apply { sourceDao.getAllOnce().forEach { put(sourceJson(it, seal)) } })
                    put("links", JSONArray().apply { sourceDao.allLinks().forEach { put(JSONObject().put("profileId", it.profileId).put("sourceId", it.sourceId)) } })
                    put("epgSources", epgSources.exportJson()) // standalone EPG feeds ride with sources
                    put("startupModes", settings.exportStartupModes()) // per-profile landing, keyed by profile id
                    put("customizePins", settings.exportCustomizePins()) // per-profile Customize PIN lock (optional block)
                    // Per-source auto-refresh selections + default source, keyed by the preserved ids.
                    put("playlistAutoRefresh", settings.exportPlaylistAutoRefresh())
                    put("epgAutoRefresh", settings.exportEpgAutoRefresh())
                    settings.currentDefaultSourceId()?.let { put("defaultSourceId", it) }
                }
                if (Section.CUSTOMIZE in sections) {
                    put("customizations", JSONObject().apply { customize.exportAll().forEach { (k, v) -> put(k, v) } })
                }
                // Favorites / history / resume positions, exported with stable keys (see UserDataResolver).
                val kinds = kindsFor(sections)
                if (kinds.isNotEmpty()) put("userData", userData.exportAll(kinds))
                if (Section.SETTINGS in sections) {
                    val s = settings.exportSettings() // non-secret keys, incl. proxy host/port/user/enabled
                    // Proxy password rides here as an encrypted object (key not in the settings whitelist,
                    // so importSettings ignores it); omitted entirely when there is no passphrase.
                    val proxyPass = settings.currentProxyPassword()
                    if (seal != null && proxyPass.isNotEmpty()) s.put("proxy_pass_enc", seal(proxyPass))
                    put("settings", s)
                    // Per-item "compatibility mode" engine pins (Live + VOD). Keyed by stream URL, so no
                    // id remapping needed on restore. Optional block — older readers just ignore it.
                    put("compatMode", JSONObject().apply {
                        put("liveMpvUrls", JSONArray(forceMpvStore.exportUrls().toList()))
                        put("vodMpvUrls", JSONArray(vodEngineStore.exportMpvUrls().toList()))
                        put("vodExoUrls", JSONArray(vodEngineStore.exportExoUrls().toList()))
                    })
                }
            }
            if (!folder.exists()) folder.mkdirs()
            val out = File(folder, "owntv-backup.json")
            out.writeText(root.toString(2))
            out.absolutePath
        }
    }

    /** Result of inspecting a backup file: which sections it holds, and whether secrets are encrypted. */
    data class Inspection(val sections: Set<Section>, val encrypted: Boolean)

    /** Thrown when a backup is encrypted and the supplied passphrase is wrong (or missing where required). */
    class WrongPasswordException : Exception("Wrong backup password")

    /** What a backup file contains + whether it carries encrypted secrets (older files have no "sections"). */
    suspend fun sectionsIn(file: File): Result<Inspection> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(file.readText())
            val out = mutableSetOf<Section>()
            if (root.has("profiles") || root.has("sources")) out += Section.SOURCES
            if (root.optJSONObject("customizations")?.keys()?.hasNext() == true) out += Section.CUSTOMIZE
            if (root.optJSONObject("settings")?.keys()?.hasNext() == true) out += Section.SETTINGS
            root.optJSONArray("userData")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (arr.getJSONObject(i).optString("kind")) {
                        "fav" -> out += Section.FAVORITES
                        "his" -> out += Section.HISTORY
                        "prog" -> out += Section.RESUME
                    }
                }
            }
            if (out.isEmpty()) error("Not an OwnTV backup file")
            Inspection(out, encrypted = root.has("crypto"))
        }
    }

    /**
     * Applies the chosen [sections] of the file (only those it actually contains).
     *
     * For encrypted backups: if [backupPassword] is provided it is validated BEFORE the destructive
     * source wipe — a wrong passphrase fails fast with [WrongPasswordException] and changes nothing. If
     * the passphrase is null/blank on an encrypted backup, non-secret data still restores and secret
     * fields are left blank (the caller tells the user to re-enter passwords). Legacy v5 backups with
     * plaintext passwords import exactly as before (no `crypto` block ⇒ strings treated as plaintext).
     */
    suspend fun import(
        file: File,
        sections: Set<Section> = Section.entries.toSet(),
        backupPassword: String? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(file.readText())
            val crypto = root.optJSONObject("crypto")
            val pass = backupPassword?.takeIf { it.isNotBlank() }

            // Derive + validate the key up front, before any destructive write.
            val key = if (crypto != null && pass != null) {
                BackupCrypto.deriveKey(pass, crypto) ?: throw WrongPasswordException()
            } else null
            if (key != null && !validatePassphrase(root, key)) throw WrongPasswordException()
            // unseal: decrypt an encrypted secret object; null key (skip) or legacy plaintext returns as-is.
            val unseal: (Any?) -> String? = { v ->
                when {
                    BackupCrypto.isEncrypted(v) -> if (key != null) runCatching { BackupCrypto.decrypt(key, v as JSONObject) }.getOrNull() else null
                    v is String -> v.takeIf { it.isNotEmpty() }
                    else -> null
                }
            }

            var count = 0

            if (Section.SOURCES in sections && (root.has("profiles") || root.has("sources"))) {
                val profiles = root.optJSONArray("profiles") ?: JSONArray()
                val sources = root.optJSONArray("sources") ?: JSONArray()
                val links = root.optJSONArray("links") ?: JSONArray()

                profileDao.getAllOnce().forEach { profile -> runCatching { launcherIntegrationRepository.clearProfile(profile.id) } }
                profileDao.deleteAll()       // cascades favorites/history/progress/profile_source
                sourceDao.deleteAllSources() // cascades content + profile_source

                for (i in 0 until profiles.length()) profileDao.insert(profileFrom(profiles.getJSONObject(i)))
                for (i in 0 until sources.length()) sourceDao.insert(sourceFrom(sources.getJSONObject(i), unseal))
                for (i in 0 until links.length()) {
                    val l = links.getJSONObject(i)
                    sourceDao.link(ProfileSourceCrossRef(profileId = l.getLong("profileId"), sourceId = l.getLong("sourceId")))
                }
                epgSources.importJson(root.optString("epgSources").takeIf { it.isNotBlank() })
                val profileIds = profileDao.getAllOnce().map { it.id }.toSet()
                profileIds.firstOrNull()?.let { settings.setActiveProfile(it) }
                root.optJSONObject("startupModes")?.let { settings.importStartupModes(it, profileIds) }
                root.optJSONObject("customizePins")?.let { settings.importCustomizePins(it, profileIds) }
                // Auto-refresh maps + default source: ids not present after restore are dropped,
                // unknown enum values fall back to OFF. Absent keys (older backups) leave defaults.
                val sourceIds = sourceDao.getAllOnce().map { it.id }.toSet()
                root.optJSONObject("playlistAutoRefresh")?.let { settings.importPlaylistAutoRefresh(it, sourceIds) }
                root.optJSONObject("epgAutoRefresh")?.let { settings.importEpgAutoRefresh(it, epgSources.getAll().map { s -> s.id }.toSet()) }
                if (root.has("defaultSourceId")) settings.importDefaultSource(root.getLong("defaultSourceId"), sourceIds)
                count += profiles.length() + sources.length()
            }

            if (Section.CUSTOMIZE in sections) {
                root.optJSONObject("customizations")?.let { o ->
                    val cust = HashMap<String, String>()
                    o.keys().forEach { k -> cust[k] = o.getString(k) }
                    customize.importAll(cust)
                    count += cust.size
                }
            }

            // Favorites/history/progress: stashed as pending records — they attach automatically as
            // the post-restore syncs repopulate the content tables (UserDataResolver.resolvePending).
            val kinds = kindsFor(sections)
            if (kinds.isNotEmpty()) {
                root.optJSONArray("userData")?.let { arr ->
                    val filtered = JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        if (e.optString("kind") in kinds) filtered.put(e)
                    }
                    userData.importAll(filtered)
                    count += filtered.length()
                }
            }

            if (Section.SETTINGS in sections) {
                root.optJSONObject("settings")?.let { s ->
                    settings.importSettings(s) // non-secret keys (incl. proxy host/port/user/enabled)
                    // Proxy password: decrypt if we have a key; if encrypted but no key, leave blank.
                    if (s.has("proxy_pass_enc")) {
                        unseal(s.opt("proxy_pass_enc"))?.let { settings.setProxyPassword(it) }
                    }
                    count += s.length()
                }
                // Per-item compatibility-mode engine pins. Optional; merged (union) into the current
                // pins so a restore never drops locally-set pins. Corrupt/non-string entries ignored.
                root.optJSONObject("compatMode")?.let { c ->
                    runCatching { forceMpvStore.importUrls(jsonStrings(c.optJSONArray("liveMpvUrls"))) }
                    runCatching {
                        vodEngineStore.importUrls(
                            jsonStrings(c.optJSONArray("vodMpvUrls")),
                            jsonStrings(c.optJSONArray("vodExoUrls")),
                        )
                    }
                }
            }
            count
        }
    }

    /** Confirms the derived key opens at least one encrypted secret in the file (GCM tag check). */
    private fun validatePassphrase(root: JSONObject, key: javax.crypto.SecretKey): Boolean {
        firstEncryptedSecret(root)?.let { sealed ->
            return runCatching { BackupCrypto.decrypt(key, sealed); true }.getOrDefault(false)
        }
        return true // crypto block but no actual encrypted field — nothing to validate against
    }

    /** Finds the first encrypted secret object in the file (a source password or the proxy password). */
    private fun firstEncryptedSecret(root: JSONObject): JSONObject? {
        root.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pw = arr.getJSONObject(i).opt("password")
                if (BackupCrypto.isEncrypted(pw)) return pw as JSONObject
            }
        }
        root.optJSONObject("settings")?.opt("proxy_pass_enc")?.let { if (BackupCrypto.isEncrypted(it)) return it as JSONObject }
        return null
    }

    private fun kindsFor(sections: Set<Section>): Set<String> = buildSet {
        if (Section.FAVORITES in sections) add("fav")
        if (Section.HISTORY in sections) add("his")
        if (Section.RESUME in sections) add("prog")
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

    private fun sourceJson(s: SourceEntity, seal: ((String) -> JSONObject)?) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("type", s.type.name); put("url", s.url)
        put("username", s.username ?: JSONObject.NULL)
        // Password: encrypted object when a passphrase was given, otherwise omitted (never plaintext).
        val pw = s.password?.takeIf { it.isNotEmpty() }
        put("password", if (pw != null && seal != null) seal(pw) else JSONObject.NULL)
        put("userAgent", s.userAgent ?: JSONObject.NULL); put("epgUrl", s.epgUrl ?: JSONObject.NULL)
        put("createdAt", s.createdAt); put("lastSyncAt", s.lastSyncAt ?: JSONObject.NULL)
    }

    private fun sourceFrom(o: JSONObject, unseal: (Any?) -> String?) = SourceEntity(
        id = o.getLong("id"), name = o.getString("name"),
        type = runCatching { SourceType.valueOf(o.getString("type")) }.getOrDefault(SourceType.M3U),
        url = o.getString("url"), username = o.optStringOrNull("username"),
        password = if (o.isNull("password")) null else unseal(o.opt("password")),
        userAgent = o.optStringOrNull("userAgent"), epgUrl = o.optStringOrNull("epgUrl"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        lastSyncAt = if (o.isNull("lastSyncAt")) null else o.optLong("lastSyncAt"),
    )
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Reads a JSON array as a list of non-blank strings, tolerating nulls/non-string entries. */
private fun jsonStrings(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val v = arr.opt(i)
        if (v is String && v.isNotBlank()) out += v
    }
    return out
}
