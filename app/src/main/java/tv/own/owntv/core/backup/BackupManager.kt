package tv.own.owntv.core.backup

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * Phase 12 — backup & restore of the painful-to-re-enter setup: **profiles** (name/avatar/kids/PIN),
 * **sources** (URLs + credentials + per-source UA) and their profile links, plus per-profile
 * **customizations** (hidden/renamed/reordered categories & channels), favorites, watch history,
 * resume positions and manual Move positions — as a JSON file. The user picks which [Section]s to
 * include on export and which
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
    private val forceMpvStore: tv.own.owntv.core.player.ForceMpvStore,
    private val vodEngineStore: tv.own.owntv.core.player.VodEngineStore,
    private val db: tv.own.owntv.core.database.OwnTVDatabase,
    private val tmdbOverrides: tv.own.owntv.core.metadata.MetadataOverrideStore,
    private val metadataDao: tv.own.owntv.core.database.dao.MetadataDao,
    private val openSubAuth: tv.own.owntv.core.subtitles.OpenSubtitlesAuthStore,
) {
    /** What a backup can contain; the user multi-selects these for export and restore. Profiles are
     *  NOT a section: every backup is inherently profile-based — the export flow's first step picks
     *  which profiles to include (PIN-verified for locked non-active ones), and the ticked profiles'
     *  rows always ride in the file. */
    enum class Section(val label: String, val desc: String) {
        SOURCES("Sources", "Playlists, EPG feeds and credentials"),
        CUSTOMIZE("Customizations", "Hidden/renamed/reordered categories, channels, EPG matches & custom TMDB names"),
        FAVORITES("Favorites", "Starred channels, movies and series"),
        HISTORY("Watch history", "Recently watched lists"),
        RESUME("Resume positions", "Where you stopped in movies & episodes"),
        MANUAL_REORDER("Manual reorder", "Your Move up/down positions for channels, movies and series"),
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
        /** Profiles to include (PIN-authorized by the caller). Null = all (legacy callers/tests). */
        profileIds: Set<Long>? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // Profile scoping: only the ticked profiles' rows and per-profile data enter the file.
            val allProfiles = profileDao.getAllOnce()
            val profiles = if (profileIds == null) allProfiles else allProfiles.filter { it.id in profileIds }
            val pids = profiles.map { it.id }.toSet()
            val pidKeys = pids.map { it.toString() }.toSet()
            val allLinks = sourceDao.allLinks().filter { it.profileId in pids }
            val linkedSourceIds = allLinks.map { it.sourceId }.toSet()
            // Set up field encryption only if a passphrase was provided.
            val pass = backupPassword?.takeIf { it.isNotBlank() }
            val salt = if (pass != null) BackupCrypto.newSalt() else null
            val key = if (pass != null && salt != null) BackupCrypto.deriveKey(pass.toCharArray(), salt, BackupCrypto.ITERATIONS) else null
            val seal: ((String) -> JSONObject)? = key?.let { k -> { plain -> BackupCrypto.encrypt(k, plain) } }

            val root = JSONObject().apply {
                put("version", 13) // v13: sources.syncLive/Movies/Series. v12: per-profile OpenSubtitles login (encrypted-only). v11: profile-scoped export. v10: sources.mac. v9: custom TMDB names, encrypted TMDB key
                put("sections", JSONArray().apply { sections.forEach { put(it.name) } })
                if (salt != null) put("crypto", BackupCrypto.cryptoBlock(salt))
                // Ticked profiles always ride (backup is profile-based); restore needs SOURCES to apply them.
                put("profiles", JSONArray().apply { profiles.forEach { put(profileJson(it)) } })
                if (Section.SOURCES in sections) {
                    // Only sources at least one ticked profile uses, and only ticked profiles' links.
                    put("sources", JSONArray().apply { sourceDao.getAllOnce().filter { it.id in linkedSourceIds }.forEach { put(sourceJson(it, seal)) } })
                    put("links", JSONArray().apply { allLinks.forEach { put(JSONObject().put("profileId", it.profileId).put("sourceId", it.sourceId)) } })
                    put("epgSources", epgSources.exportJson()) // standalone EPG feeds ride with sources
                    put("startupModes", filterByProfile(settings.exportStartupModes(), pidKeys)) // per-profile landing
                    put("customizePins", filterByProfile(settings.exportCustomizePins(), pidKeys)) // per-profile Customize PIN lock
                    // Per-source auto-refresh selections + default source, keyed by the preserved ids.
                    put("playlistAutoRefresh", settings.exportPlaylistAutoRefresh())
                    put("epgAutoRefresh", settings.exportEpgAutoRefresh())
                    put("epgUseLogos", settings.exportEpgUseLogos())
                    settings.currentDefaultSourceId()?.let { put("defaultSourceId", it) }
                }
                if (Section.CUSTOMIZE in sections) {
                    // Customization entries are keyed "cust_<profileId>_<TYPE>" — keep ticked profiles only.
                    put(
                        "customizations",
                        JSONObject().apply {
                            customize.exportAll()
                                .filterKeys { k -> k.removePrefix("cust_").substringBefore('_') in pidKeys }
                                .forEach { (k, v) -> put(k, v) }
                        },
                    )
                    put("homeConfigs", filterByProfile(settings.exportHomeConfigs(), pidKeys))
                    put("hideNewCategories", filterByProfile(settings.exportHideNewCategories(), pidKeys))
                    // User-corrected TMDB titles/years. Keyed by "type:sourceId:remoteId|name" — source ids
                    // are preserved on restore, so the map rides verbatim. Optional block; older readers ignore it.
                    tmdbOverrides.exportJson().takeIf { it.isNotBlank() }?.let { put("tmdbOverrides", it) }
                }
                // Favorites / history / resume positions, exported with stable keys (see UserDataResolver),
                // filtered to the ticked profiles (each record carries its owner in "p").
                val kinds = kindsFor(sections)
                if (kinds.isNotEmpty()) {
                    val all = userData.exportAll(kinds)
                    val filtered = JSONArray()
                    for (i in 0 until all.length()) {
                        val e = all.getJSONObject(i)
                        if (e.optLong("p", -1) in pids) filtered.put(e)
                    }
                    put("userData", filtered)
                }
                if (Section.SETTINGS in sections) {
                    val s = settings.exportSettings() // non-secret keys, incl. proxy host/port/user/enabled
                    // Proxy password rides here as an encrypted object (key not in the settings whitelist,
                    // so importSettings ignores it); omitted entirely when there is no passphrase.
                    val proxyPass = settings.currentProxyPassword()
                    if (seal != null && proxyPass.isNotEmpty()) s.put("proxy_pass_enc", seal(proxyPass))
                    // The user's own TMDB API key: same secret policy — encrypted with a passphrase, else omitted.
                    val tmdbKey = settings.currentTmdbApiKey()
                    if (seal != null && tmdbKey.isNotEmpty()) s.put("tmdb_key_enc", seal(tmdbKey))
                    put("settings", s)
                    // Per-item "compatibility mode" engine pins (Live + VOD). Keyed by stream URL, so no
                    // id remapping needed on restore. Optional block — older readers just ignore it.
                    put("compatMode", JSONObject().apply {
                        put("liveMpvUrls", JSONArray(forceMpvStore.exportUrls().toList()))
                        put("vodMpvUrls", JSONArray(vodEngineStore.exportMpvUrls().toList()))
                        put("vodExoUrls", JSONArray(vodEngineStore.exportExoUrls().toList()))
                    })
                    // Per-profile OpenSubtitles login (username + password/token). A secret: the whole
                    // session blob is encrypted with the backup passphrase, so it rides ONLY when one is
                    // set — omitted otherwise, exactly like the source/proxy/TMDB secrets. Ticked profiles only.
                    if (seal != null) {
                        put("openSubtitles", JSONArray().apply {
                            profiles.forEach { p ->
                                openSubAuth.exportJson(p.id)?.let { blob ->
                                    put(JSONObject().put("p", p.id).put("session", seal(blob.toString())))
                                }
                            }
                        })
                    }
                }
            }
            if (!folder.exists()) folder.mkdirs()
            writeAtomically(File(folder, BACKUP_FILENAME), root.toString(2))
        }
    }

    /**
     * Read a backup file, falling back to the rotated `.bak` when the primary is missing or is not
     * parseable JSON — the case an interrupted pre-atomic export used to leave behind.
     */
    private fun readBackupJson(file: File): JSONObject {
        val primary = runCatching { JSONObject(file.readText()) }
        if (primary.isSuccess) return primary.getOrThrow()
        val bak = File(file.parentFile, "${file.name}$BAK_SUFFIX")
        if (bak.exists()) {
            runCatching { JSONObject(bak.readText()) }.getOrNull()?.let { return it }
        }
        throw primary.exceptionOrNull() ?: IllegalStateException("Not an OwnTV backup file")
    }

    /** Result of inspecting a backup file: which sections it holds, and whether secrets are encrypted. */
    data class Inspection(val sections: Set<Section>, val encrypted: Boolean)

    /**
     * Outcome of a restore: how many rows/entries were applied, and how many `sources[]` entries were
     * left out because this build doesn't know their [SourceType] (B4 — see [sourceFrom]).
     */
    data class ImportSummary(val items: Int, val skippedSources: Int = 0) {
        /** A sentence to append to the user-facing restore message, empty when nothing was skipped. */
        val skippedNote: String
            get() = when (skippedSources) {
                0 -> ""
                1 -> " 1 source was skipped — it uses a playlist type this version doesn't support."
                else -> " $skippedSources sources were skipped — they use playlist types this version doesn't support."
            }
    }

    /** Thrown when a backup is encrypted and the supplied passphrase is wrong (or missing where required). */
    class WrongPasswordException : Exception("Wrong backup password")

    /** What a backup file contains + whether it carries encrypted secrets (older files have no "sections"). */
    suspend fun sectionsIn(file: File): Result<Inspection> = withContext(Dispatchers.IO) {
        runCatching {
            val root = readBackupJson(file)
            val out = mutableSetOf<Section>()
            // v11+ files always carry "profiles" (backup is profile-based); only "sources" marks the
            // Sources section. Older files wrote both together, so this reads them identically.
            if (root.has("sources")) out += Section.SOURCES
            if (
                root.optJSONObject("customizations")?.keys()?.hasNext() == true ||
                root.optJSONObject("homeConfigs")?.keys()?.hasNext() == true ||
                root.optJSONObject("hideNewCategories")?.keys()?.hasNext() == true ||
                root.optString("tmdbOverrides").isNotBlank()
            ) out += Section.CUSTOMIZE
            if (root.optJSONObject("settings")?.keys()?.hasNext() == true) out += Section.SETTINGS
            root.optJSONArray("userData")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (arr.getJSONObject(i).optString("kind")) {
                        "fav" -> out += Section.FAVORITES
                        "his" -> out += Section.HISTORY
                        "prog" -> out += Section.RESUME
                        "order" -> out += Section.MANUAL_REORDER
                    }
                }
            }
            if (out.isEmpty()) error("Not an OwnTV backup file")
            Inspection(out, encrypted = root.has("crypto"))
        }
    }

    /**
     * Applies the chosen [sections] of the file (only those it actually contains) as a MERGE — a
     * restore never deletes anything that already exists on the device (owner decision, 2026-07-18):
     *
     * - Profiles are matched **by name** (case-insensitive): a match is updated in place (avatar,
     *   kids flag, PIN); a new name is added (with a fresh id if the file's id is taken). Device
     *   profiles not in the file are untouched. Ids can't be the match key — they're per-device
     *   auto-increments, so "id 1" on two devices is usually two different people.
     * - Sources are matched by type+URL+username: matches keep the device row (secrets updated when
     *   the file carries them); unknown sources are added. Nothing is wiped.
     * - Every id in the file (profile/source/EPG-source) is remapped to its device id before any
     *   per-profile or per-source data (links, favorites/history/resume, customizations, custom TMDB
     *   names, auto-refresh, startup modes, Customize PINs, home configs) is applied.
     *
     * For encrypted backups: if [backupPassword] is provided it is validated BEFORE any write — a
     * wrong passphrase fails fast with [WrongPasswordException] and changes nothing. If the
     * passphrase is null/blank on an encrypted backup, non-secret data still restores and secret
     * fields are left blank (the caller tells the user to re-enter passwords). Legacy v5 backups with
     * plaintext passwords import exactly as before (no `crypto` block ⇒ strings treated as plaintext).
     */
    suspend fun import(
        file: File,
        sections: Set<Section> = Section.entries.toSet(),
        backupPassword: String? = null,
    ): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val root = readBackupJson(file)
            val crypto = root.optJSONObject("crypto")
            val pass = backupPassword?.takeIf { it.isNotBlank() }
            var existingProfileIds = profileDao.getAllOnce().map { it.id }.toSet()

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
            // Sources whose "type" this build can't parse: skipped rather than coerced (B4), and
            // reported back so the restore message can say what was left out.
            var skippedSources = 0

            // Id remapping (file id → device id). Filled during the profile/source merge below. When
            // the SOURCES section isn't being restored, matching still runs read-only so the other
            // sections can attach to the right device rows; unmatched ids fall through unchanged.
            val profileIdMap = HashMap<Long, Long>()
            val sourceIdMap = HashMap<Long, Long>()
            var epgIdMap: Map<Long, Long> = emptyMap()

            val fileProfiles = root.optJSONArray("profiles") ?: JSONArray()
            val fileSources = root.optJSONArray("sources") ?: JSONArray()
            val applySources = Section.SOURCES in sections && (root.has("profiles") || root.has("sources"))

            // Interrupted-restore marker (B2): set before the first write of any kind, cleared only
            // after the last one. A restore spans the database AND several DataStore files, so no
            // single transaction can cover it; the marker is what makes a half-applied restore
            // visible instead of silent (the shell prompts about it at the next launch).
            settings.markRestoreStarted("${file.name} · ${sections.joinToString(", ") { it.name }}")
            Log.i(
                TAG,
                "Restore start file=${file.name} sections=${sections.joinToString(",") { it.name }} " +
                    "encrypted=${crypto != null} key=${key != null} profiles=${fileProfiles.length()} sources=${fileSources.length()}",
            )

            // MERGE-restore (owner decision): a restore never deletes existing profiles or sources.
            // One transaction wraps the profile/source/link row writes — the part where a partial
            // apply would be actively wrong (a source inserted but its profile link missing). The
            // DataStore-backed sections below (settings, customizations, engine pins, logins) can't
            // join it, and neither can the user-data resolve, which is deliberately chunked (B3) so
            // a large restore doesn't hold one write transaction for its whole duration.
            db.withTransaction {
                // --- profiles: match by NAME (case-insensitive) — ids are per-device counters and
                // collide across devices, so they can't identify a person. Match → update in place;
                // new name → insert (keeping the file id only when it's free).
                val deviceProfiles = profileDao.getAllOnce()
                val byName = deviceProfiles.associateBy { profileMatchKey(it.name) }
                val takenProfileIds = deviceProfiles.map { it.id }.toMutableSet()
                for (i in 0 until fileProfiles.length()) {
                    val incoming = profileFrom(fileProfiles.getJSONObject(i))
                    val existing = byName[profileMatchKey(incoming.name)]
                    when {
                        existing != null -> {
                            if (applySources) {
                                profileDao.update(
                                    existing.copy(
                                        avatarColor = incoming.avatarColor, avatarId = incoming.avatarId,
                                        isKids = incoming.isKids, pinHash = incoming.pinHash,
                                    ),
                                )
                            }
                            profileIdMap[incoming.id] = existing.id
                        }
                        applySources -> {
                            val keepId = incoming.id > 0 && incoming.id !in takenProfileIds
                            val rowId = profileDao.insert(if (keepId) incoming else incoming.copy(id = 0))
                            val deviceId = if (keepId) incoming.id else rowId
                            takenProfileIds += deviceId
                            profileIdMap[incoming.id] = deviceId
                        }
                        // else: not restoring SOURCES and no matching profile on the device — leave
                        // unmapped; the per-profile blocks below skip unmapped ids safely.
                    }
                }

                // --- sources: match by type+URL+username. Match → keep the device row (refresh the
                // secrets/extras the file carries); unknown → insert. Nothing is wiped.
                val deviceSources = sourceDao.getAllOnce()
                val byKey = deviceSources.associateBy { sourceMatchKey(it.type.name, it.url, it.username) }
                val takenSourceIds = deviceSources.map { it.id }.toMutableSet()
                for (i in 0 until fileSources.length()) {
                    val incoming = sourceFrom(fileSources.getJSONObject(i), unseal)
                    if (incoming == null) {
                        skippedSources++
                        continue
                    }
                    val existing = byKey[sourceMatchKey(incoming.type.name, incoming.url, incoming.username)]
                    when {
                        existing != null -> {
                            if (applySources) {
                                sourceDao.update(
                                    existing.copy(
                                        password = incoming.password ?: existing.password,
                                        mac = incoming.mac ?: existing.mac,
                                        userAgent = incoming.userAgent ?: existing.userAgent,
                                        epgUrl = incoming.epgUrl ?: existing.epgUrl,
                                    ),
                                )
                            }
                            sourceIdMap[incoming.id] = existing.id
                        }
                        applySources -> {
                            val keepId = incoming.id > 0 && incoming.id !in takenSourceIds
                            val toInsert = if (keepId) incoming else incoming.copy(id = 0)
                            // A restored backup starts with empty catalog tables. Nullifying lastSyncAt ensures
                            // the first sync is treated as a fresh sync, preserving the restored category settings.
                            val rowId = sourceDao.insert(toInsert.copy(lastSyncAt = null))
                            val deviceId = if (keepId) incoming.id else rowId
                            takenSourceIds += deviceId
                            sourceIdMap[incoming.id] = deviceId
                        }
                    }
                }

                if (applySources) {
                    val links = root.optJSONArray("links") ?: JSONArray()
                    for (i in 0 until links.length()) {
                        val l = links.getJSONObject(i)
                        val pid = profileIdMap[l.getLong("profileId")] ?: continue
                        val sid = sourceIdMap[l.getLong("sourceId")] ?: continue
                        sourceDao.link(ProfileSourceCrossRef(profileId = pid, sourceId = sid)) // IGNORE on dup
                    }
                }
            }

            if (applySources) {
                // EPG sources merge by URL; the returned map remaps the auto-refresh keys below.
                epgIdMap = epgSources.mergeJson(root.optString("epgSources").takeIf { it.isNotBlank() })

                val profileIds = profileDao.getAllOnce().map { it.id }.toSet()
                // Only a device with no usable active profile (fresh install) adopts a restored one.
                if (settings.activeProfileId.first() !in profileIds) {
                    profileIdMap.values.firstOrNull()?.let { settings.setActiveProfile(it) }
                }
                root.optJSONObject("startupModes")?.let { settings.importStartupModes(remapKeys(it, profileIdMap), profileIds) }
                root.optJSONObject("customizePins")?.let { settings.importCustomizePins(remapKeys(it, profileIdMap), profileIds) }
                existingProfileIds = profileIds
                // Auto-refresh maps + default source, remapped to device ids; entries whose ids didn't
                // survive the merge are dropped; the device's other choices are kept (merge).
                val sourceIds = sourceDao.getAllOnce().map { it.id }.toSet()
                root.optJSONObject("playlistAutoRefresh")?.let { settings.importPlaylistAutoRefresh(remapKeys(it, sourceIdMap), sourceIds) }
                root.optJSONObject("epgAutoRefresh")?.let { settings.importEpgAutoRefresh(remapKeys(it, epgIdMap), epgSources.getAll().map { s -> s.id }.toSet()) }
                root.optJSONObject("epgUseLogos")?.let { settings.importEpgUseLogos(remapKeys(it, epgIdMap), epgSources.getAll().map { s -> s.id }.toSet()) }
                if (root.has("defaultSourceId")) {
                    val mapped = sourceIdMap[root.getLong("defaultSourceId")] ?: root.getLong("defaultSourceId")
                    settings.importDefaultSource(mapped, sourceIds)
                }
                count += fileProfiles.length() + fileSources.length() - skippedSources
            }

            if (Section.CUSTOMIZE in sections) {
                root.optJSONObject("customizations")?.let { o ->
                    // Remap "cust_<pid>_<TYPE>" keys and the "<sourceId>:…" content keys inside each
                    // value, then MERGE — other profiles' customizations stay untouched.
                    val cust = HashMap<String, String>()
                    o.keys().forEach { k ->
                        if (!k.startsWith("cust_")) return@forEach
                        val body = k.removePrefix("cust_")
                        val filePid = body.substringBefore('_').toLongOrNull() ?: return@forEach
                        val pid = profileIdMap[filePid] ?: filePid
                        cust["cust_${pid}_${body.substringAfter('_')}"] = remapCustomizationValue(o.getString(k), sourceIdMap)
                    }
                    customize.mergeAll(cust)
                    count += cust.size
                }
                root.optJSONObject("homeConfigs")?.let { settings.importHomeConfigs(remapKeys(it, profileIdMap), existingProfileIds) }
                root.optJSONObject("hideNewCategories")?.let { settings.importHideNewCategories(remapKeys(it, profileIdMap), existingProfileIds) }
                // Custom TMDB names: merge (backup wins per key), then drop any cached match/details stored
                // under the imported keys so the corrected title is re-fetched instead of showing stale art.
                // Keys embed the source id ("movie:<sourceId>:…") — remap before merging.
                root.optString("tmdbOverrides").takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching {
                        val touched = tmdbOverrides.importJson(remapTmdbOverrideKeys(raw, sourceIdMap))
                        // One transaction for the cache invalidation: these two deletes per key are
                        // a pair — a half-done pass would leave a stale details row keyed to a match
                        // that's already gone, which reads back as the old title's artwork.
                        db.withTransaction {
                            touched.forEach { k ->
                                metadataDao.deleteMatch(k)
                                metadataDao.deleteCache(k)
                            }
                        }
                    }
                }
            }

            // Favorites/history/progress: stashed as pending records — they attach automatically as
            // the post-restore syncs repopulate the content tables (UserDataResolver.resolvePending).
            // Each record's profile ("p") and source ("src") ids are remapped to the merged device ids;
            // records whose profile has no home on this device are skipped.
            val kinds = kindsFor(sections)
            if (kinds.isNotEmpty()) {
                root.optJSONArray("userData")?.let { arr ->
                    val filtered = JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        if (e.optString("kind") !in kinds) continue
                        val fileP = e.optLong("p", -1)
                        val p = profileIdMap[fileP] ?: if (applySources) continue else fileP
                        e.put("p", p)
                        val src = e.optLong("src", -1)
                        sourceIdMap[src]?.let { e.put("src", it) }
                        filtered.put(e)
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
                    if (s.has("tmdb_key_enc")) {
                        unseal(s.opt("tmdb_key_enc"))?.let { settings.setTmdbApiKey(it) }
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
                // Per-profile OpenSubtitles login: decrypt each blob and store it under the remapped
                // device profile id. Encrypted-only, so it's skipped when there's no key (no passphrase).
                // Only profiles that exist on this device get one; an expired token later self-heals via
                // the store's silent re-login when a password rode along ("Stay signed in").
                root.optJSONArray("openSubtitles")?.let { arr ->
                    val deviceProfileIds = profileDao.getAllOnce().map { it.id }.toSet()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val filePid = e.optLong("p", -1)
                        val pid = profileIdMap[filePid] ?: filePid
                        if (pid !in deviceProfileIds) continue
                        unseal(e.opt("session"))?.let { plain ->
                            runCatching { openSubAuth.importJson(pid, JSONObject(plain)) }
                        }
                    }
                }
            }
            settings.clearRestoreMarker()
            Log.i(TAG, "Restore done items=$count skippedSources=$skippedSources")
            ImportSummary(items = count, skippedSources = skippedSources)
        }
    }

    /** Confirms the derived key opens at least one encrypted secret in the file (GCM tag check). */
    private fun validatePassphrase(root: JSONObject, key: javax.crypto.SecretKey): Boolean {
        firstEncryptedSecret(root)?.let { sealed ->
            return runCatching { BackupCrypto.decrypt(key, sealed); true }.getOrDefault(false)
        }
        return true // crypto block but no actual encrypted field — nothing to validate against
    }

    /** Finds the first encrypted secret object in the file (a source password, a Stalker MAC, or the proxy/TMDB key). */
    private fun firstEncryptedSecret(root: JSONObject): JSONObject? {
        root.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val src = arr.getJSONObject(i)
                val pw = src.opt("password")
                if (BackupCrypto.isEncrypted(pw)) return pw as JSONObject
                // A Stalker source's MAC is its only secret (password is null), so probe it too —
                // otherwise an all-Stalker backup couldn't validate the passphrase.
                val mac = src.opt("mac")
                if (BackupCrypto.isEncrypted(mac)) return mac as JSONObject
            }
        }
        root.optJSONObject("settings")?.opt("proxy_pass_enc")?.let { if (BackupCrypto.isEncrypted(it)) return it as JSONObject }
        root.optJSONObject("settings")?.opt("tmdb_key_enc")?.let { if (BackupCrypto.isEncrypted(it)) return it as JSONObject }
        // A per-profile OpenSubtitles login is encrypted too — probe it so an all-OpenSubtitles backup validates.
        root.optJSONArray("openSubtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sealed = arr.getJSONObject(i).opt("session")
                if (BackupCrypto.isEncrypted(sealed)) return sealed as JSONObject
            }
        }
        return null
    }

    private fun kindsFor(sections: Set<Section>): Set<String> = buildSet {
        if (Section.FAVORITES in sections) add("fav")
        if (Section.HISTORY in sections) add("his")
        if (Section.RESUME in sections) add("prog")
        if (Section.MANUAL_REORDER in sections) add("order")
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
        // Stalker MAC: same secret policy as the password — encrypted with a passphrase, else omitted.
        val macVal = s.mac?.takeIf { it.isNotEmpty() }
        put("mac", if (macVal != null && seal != null) seal(macVal) else JSONObject.NULL)
        put("userAgent", s.userAgent ?: JSONObject.NULL); put("epgUrl", s.epgUrl ?: JSONObject.NULL)
        put("syncLive", s.syncLive); put("syncMovies", s.syncMovies); put("syncSeries", s.syncSeries)
        put("createdAt", s.createdAt); put("lastSyncAt", s.lastSyncAt ?: JSONObject.NULL)
    }

    /**
     * Maps one `sources[]` entry, or **null when its `type` isn't a [SourceType] this build knows**
     * (B4). It used to coerce an unknown type to [SourceType.M3U], which silently turned a newer
     * build's Stalker/other portal into a broken M3U row pointing at a portal URL — worse than not
     * restoring it, because the user has no way to tell it apart from a real playlist. Callers skip
     * and count these, and the restore summary says how many were left out.
     */
    private fun sourceFrom(o: JSONObject, unseal: (Any?) -> String?): SourceEntity? {
        val type = parseSourceType(o.optString("type")) ?: run {
            Log.w(TAG, "Restore: skipping source '${o.optString("name")}' — unknown type '${o.optString("type")}'")
            return null
        }
        return SourceEntity(
            id = o.getLong("id"), name = o.getString("name"),
            type = type,
            url = o.getString("url"), username = o.optStringOrNull("username"),
            password = if (o.isNull("password")) null else unseal(o.opt("password")),
            // Stalker MAC: restored from its encrypted block when a passphrase was given; null on backups
            // older than v10 (no "mac" key) or when the MAC was omitted (no passphrase).
            mac = if (o.isNull("mac")) null else unseal(o.opt("mac")),
            userAgent = o.optStringOrNull("userAgent"), epgUrl = o.optStringOrNull("epgUrl"),
            // Pre-v13 backups omit the flags — default On so restore matches today's behaviour.
            syncLive = if (o.has("syncLive")) o.optBoolean("syncLive", true) else true,
            syncMovies = if (o.has("syncMovies")) o.optBoolean("syncMovies", true) else true,
            syncSeries = if (o.has("syncSeries")) o.optBoolean("syncSeries", true) else true,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastSyncAt = if (o.isNull("lastSyncAt")) null else o.optLong("lastSyncAt"),
        )
    }

    companion object {
        internal const val TAG = "BackupManager"

        /**
         * The `sources[].type` rule, kept pure so it's testable: a name this build's [SourceType]
         * knows, or null. Never a fallback — see [sourceFrom] for why coercing to M3U was worse
         * than skipping.
         */
        internal fun parseSourceType(raw: String?): SourceType? =
            raw?.takeIf { it.isNotBlank() }?.let { name -> SourceType.entries.firstOrNull { it.name == name } }

        const val BACKUP_FILENAME = "owntv-backup.json"
        internal const val TMP_SUFFIX = ".tmp"
        internal const val BAK_SUFFIX = ".bak"

        /**
         * Write [text] to [target] without ever leaving a truncated file behind: content goes to a
         * `.tmp` sibling, is flushed to disk (fsync) so a power cut can't leave an empty-but-renamed
         * file, the previous good backup rotates to `.bak`, and only then does the tmp take the final
         * name. A failure anywhere drops the tmp and leaves the old backup exactly as it was.
         *
         * Returns the **final** path — callers (the export UI, the companion download) rely on that.
         */
        internal fun writeAtomically(target: File, text: String): String {
            val tmp = File(target.parentFile, "${target.name}$TMP_SUFFIX")
            try {
                java.io.FileOutputStream(tmp).use { fos ->
                    fos.write(text.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                if (target.exists()) {
                    val bak = File(target.parentFile, "${target.name}$BAK_SUFFIX")
                    bak.delete()
                    target.renameTo(bak) // best effort: a failed rotation must not block the new write
                }
                if (!tmp.renameTo(target)) error("Could not finalize backup file")
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            return target.absolutePath
        }
    }
}

// --- merge-restore id remapping helpers ---
//
// Top-level and `internal` rather than private members of BackupManager: they are pure String/JSON
// functions with no dependency on the manager's DAOs, and a merge-restore that gets one of them
// wrong silently attaches a restored profile's favorites, folder customizations or TMDB overrides to
// the WRONG source on the device. That is worth unit tests, and unit tests need them reachable.

/**
 * Identity a restored profile is merged onto: the display name, trimmed and case-folded. Case-folded
 * with Kotlin's locale-invariant [lowercase] on purpose — a Turkish-locale device must not decide
 * "Kids" and "KIDS" are different people and end up with two profiles.
 */
internal fun profileMatchKey(name: String) = name.trim().lowercase()

/**
 * Identity a restored source is merged onto. Username is part of the key because one portal commonly
 * serves several accounts; the password is not, so a re-exported backup with a rotated password
 * still updates the existing row instead of duplicating it.
 */
internal fun sourceMatchKey(type: String, url: String, username: String?) =
    "$type|${url.trim()}|${username.orEmpty()}"

/** Rewrites an id-keyed JSON map ({"<fileId>": …}) to device ids; unmapped keys pass through. */
internal fun remapKeys(o: JSONObject, idMap: Map<Long, Long>): JSONObject {
    if (idMap.isEmpty()) return o
    val out = JSONObject()
    o.keys().forEach { k ->
        val mapped = k.toLongOrNull()?.let { idMap[it] }?.toString() ?: k
        out.put(mapped, o.get(k))
    }
    return out
}

/** Rewrites the "<sourceId>:<rest>" content keys inside one SectionCustomizations JSON blob. */
internal fun remapCustomizationValue(raw: String, sourceIdMap: Map<Long, Long>): String {
    if (sourceIdMap.isEmpty()) return raw
    fun remapContentKey(k: String): String {
        val sid = k.substringBefore(':').toLongOrNull() ?: return k
        val mapped = sourceIdMap[sid] ?: return k
        return "$mapped:${k.substringAfter(':')}"
    }
    return runCatching {
        val o = JSONObject(raw)
        val out = JSONObject()
        o.keys().forEach { field ->
            when (val v = o.get(field)) {
                is JSONArray -> out.put(field, JSONArray().apply { for (i in 0 until v.length()) put(remapContentKey(v.getString(i))) })
                is JSONObject -> out.put(field, JSONObject().apply { v.keys().forEach { k -> put(remapContentKey(k), v.get(k)) } })
                else -> out.put(field, v)
            }
        }
        out.toString()
    }.getOrDefault(raw)
}

/** Rewrites "type:<sourceId>:<rest>" keys of the custom-TMDB-name map to device source ids. */
internal fun remapTmdbOverrideKeys(raw: String, sourceIdMap: Map<Long, Long>): String {
    if (sourceIdMap.isEmpty()) return raw
    return runCatching {
        val o = JSONObject(raw)
        val out = JSONObject()
        o.keys().forEach { k ->
            val parts = k.split(':', limit = 3)
            val mapped = if (parts.size == 3) {
                parts[1].toLongOrNull()?.let { sourceIdMap[it] }?.let { "${parts[0]}:$it:${parts[2]}" } ?: k
            } else k
            out.put(mapped, o.get(k))
        }
        out.toString()
    }.getOrDefault(raw)
}

/** Keeps only the entries of a profile-id-keyed JSON map that belong to the ticked profiles. */
internal fun filterByProfile(map: JSONObject, pidKeys: Set<String>): JSONObject {
    val out = JSONObject()
    map.keys().forEach { k -> if (k in pidKeys) out.put(k, map.get(k)) }
    return out
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
