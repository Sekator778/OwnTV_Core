package tv.own.owntv.features.settings.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.own.owntv.features.home.HomeConfig
import tv.own.owntv.ui.theme.AccentColor
import tv.own.owntv.ui.theme.ThemeMode
import tv.own.owntv.ui.theme.UiZoom

/** Per-profile startup landing (Phase 3 / v4.0.0). LAST_CHANNEL also covers "auto-play my channel" since
 *  it's always the one you last watched. */
enum class StartupMode(val label: String) {
    HOME("Home"), LAST_CHANNEL("Last channel"), FAVORITES("Live · Favorites")
}

/**
 * Per-source playlist auto-refresh mode. The interval entries are **staleness thresholds**, not strict
 * timers: a source is refreshed when `now - lastSyncAt >= thresholdMs`. OFF disables auto-refresh;
 * STARTUP refreshes on cold app start only; interval modes are checked on cold start and on resume.
 * [thresholdMs] has a default of null so OFF/STARTUP can be declared without an explicit value.
 */
enum class PlaylistAutoRefresh(val label: String, val thresholdMs: Long? = null) {
    OFF("Off"),
    STARTUP("Refresh at startup"),
    HOURS_6("6 hours", 6 * 3600_000L),
    HOURS_12("12 hours", 12 * 3600_000L),
    HOURS_24("24 hours", 24 * 3600_000L),
    HOURS_48("48 hours", 48 * 3600_000L);

    /** Interval (staleness-threshold) mode — checked on cold start AND on resume when threshold is exceeded. */
    val isInterval: Boolean get() = thresholdMs != null && this != STARTUP
}

/** Per-EPG-source auto-refresh mode. Same staleness-threshold semantics as [PlaylistAutoRefresh]. */
enum class EpgAutoRefresh(val label: String, val thresholdMs: Long? = null) {
    OFF("Off"),
    STARTUP("Refresh at startup"),
    HOURS_1("1 hour", 1 * 3600_000L),
    HOURS_3("3 hours", 3 * 3600_000L),
    HOURS_6("6 hours", 6 * 3600_000L),
    HOURS_12("12 hours", 12 * 3600_000L),
    HOURS_24("24 hours", 24 * 3600_000L),
    HOURS_48("48 hours", 48 * 3600_000L);

    val isInterval: Boolean get() = thresholdMs != null && this != STARTUP
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_settings")

/**
 * Persists app-level preferences. Phase 1 only needs the theme selection; this will grow to hold
 * UI zoom, custom user-agent, refresh-on-start, etc. in later phases.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val UI_ZOOM_PCT = intPreferencesKey("ui_zoom_percent")
        val ACCENT = stringPreferencesKey("accent_color")
        val ACCENT_CUSTOM = stringPreferencesKey("accent_custom")
        val AVATAR_ID = intPreferencesKey("avatar_id")
        val ACTIVE_PROFILE = longPreferencesKey("active_profile_id")
        val DEFAULT_SOURCE = longPreferencesKey("default_source_id")
        val DOWNLOAD_ROOT = stringPreferencesKey("download_root")
        val REFRESH_SOURCE_IDS = stringSetPreferencesKey("refresh_source_ids")
        // Per-source auto-refresh selections (JSON maps: { "<sourceId>": "<EnumName>" }). Replace the
        // binary refresh-on-startup set with Off/Startup + staleness thresholds. Migration-safe: the legacy
        // REFRESH_SOURCE_IDS set is read once (see migrateLegacyRefreshFlags) then ignored.
        val PLAYLIST_AUTO_REFRESH = stringPreferencesKey("playlist_auto_refresh")
        val EPG_AUTO_REFRESH = stringPreferencesKey("epg_auto_refresh")
        val REFRESH_MIGRATED = booleanPreferencesKey("refresh_migration_done")
        val LIVE_PREVIEW = booleanPreferencesKey("live_preview")
        val LIVE_PREVIEW_AUDIO = booleanPreferencesKey("live_preview_audio")
        val HDR_ENABLED = booleanPreferencesKey("hdr_enabled")
        val ANDROID_TV_HOME = booleanPreferencesKey("android_tv_home")
        // Video Player Settings
        val HW_DECODING = booleanPreferencesKey("hw_decoding")
        val VOD_PREFER_EXO = booleanPreferencesKey("vod_prefer_exo")
        val SURROUND_SOUND = booleanPreferencesKey("surround_sound")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        val DEFAULT_ZOOM = stringPreferencesKey("default_zoom")
        val SUB_SCALE = floatPreferencesKey("sub_scale")
        val AUDIO_DELAY_MS = intPreferencesKey("audio_delay_ms")
        val PREF_AUDIO_LANG = stringPreferencesKey("pref_audio_lang")
        val PREF_SUB_LANG = stringPreferencesKey("pref_sub_lang")
        // Per-section list sorting ("PLAYLIST" or "ALPHA")
        val SORT_LIVE = stringPreferencesKey("sort_live")
        val SORT_GUIDE = stringPreferencesKey("sort_guide")
        val SORT_MOVIES = stringPreferencesKey("sort_movies")
        val SORT_SERIES = stringPreferencesKey("sort_series")
        val RESUME_MODE = stringPreferencesKey("resume_mode")
        val UPDATE_CHECK_ON_START = booleanPreferencesKey("update_check_on_start")
        val CATCHUP_TZ = stringPreferencesKey("catchup_timezone")
        val CATCHUP_OFFSET_MIN = intPreferencesKey("catchup_offset_minutes")
        val ANIMATION_LEVEL = stringPreferencesKey("animation_level")
        val RESUME_LAST_CHANNEL = booleanPreferencesKey("resume_last_channel")
        val LAST_LIVE_CATEGORY = stringPreferencesKey("last_live_category")
        val LAST_LIVE_CHANNEL = androidx.datastore.preferences.core.longPreferencesKey("last_live_channel")
        val VOD_VIEW_MODE = stringPreferencesKey("vod_view_mode")
        // Global proxy (Approach 1 — one app-wide HTTP proxy). HTTP only; no per-source override yet.
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USER = stringPreferencesKey("proxy_user")
        val PROXY_PASS = stringPreferencesKey("proxy_pass")
        // Weather chip: show/hide + manual location override (blank = auto-detect from public IP).
        val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
        val WEATHER_LOCATION = stringPreferencesKey("weather_location")
        // TMDB metadata enrichment (see extras/future-plan/tmdb-metadata-plan.md). Master toggle + the two
        // advanced tiers (own key / self-host URL). Blank tier fields = use the default caching Worker.
        val METADATA_ENABLED = booleanPreferencesKey("metadata_enabled") // legacy; migrated to METADATA_MODE
        val METADATA_MODE = stringPreferencesKey("metadata_mode")
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        val METADATA_SERVER_URL = stringPreferencesKey("metadata_server_url")
    }

    // --- Live TV: remember the last focused channel so reopening lands focus back on it ---
    val lastLiveChannelId: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_LIVE_CHANNEL] ?: -1L }
    suspend fun setLastLiveChannelId(id: Long) {
        context.dataStore.edit { it[Keys.LAST_LIVE_CHANNEL] = id }
    }

    // --- Startup: per-profile landing (v4.0.0). Falls back to the legacy global resume toggle for existing
    //     users (so "Resume last channel = On" keeps working until they pick a per-profile mode). ---
    fun startupMode(profileId: Long): Flow<StartupMode> = context.dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("startup_mode_$profileId")]?.let { runCatching { StartupMode.valueOf(it) }.getOrNull() }
            ?: if (prefs[Keys.RESUME_LAST_CHANNEL] == true) StartupMode.LAST_CHANNEL else StartupMode.HOME
    }
    suspend fun setStartupMode(profileId: Long, mode: StartupMode) {
        context.dataStore.edit { it[stringPreferencesKey("startup_mode_$profileId")] = mode.name }
    }

    // --- Customize & Hidden Items: optional per-profile PIN lock on the screen (so hidden items can't
    //     be unhidden by someone else). Deliberately NOT exported in backups — a lock code shouldn't
    //     travel in a readable backup file, and a restored backup shouldn't lock anyone out. ---
    fun customizePin(profileId: Long): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("customize_pin_$profileId")]?.takeIf { it.isNotBlank() }
    }

    /** null/blank clears the lock. */
    suspend fun setCustomizePin(profileId: Long, pin: String?) {
        context.dataStore.edit { prefs ->
            val k = stringPreferencesKey("customize_pin_$profileId")
            if (pin.isNullOrBlank()) prefs.remove(k) else prefs[k] = pin.trim()
        }
    }

    // --- Home: per-profile row order / visibility / hero filters. ---
    private fun homeConfigKey(profileId: Long) = stringPreferencesKey("home_config_$profileId")

    fun homeConfig(profileId: Long): Flow<HomeConfig> = context.dataStore.data.map { prefs ->
        HomeConfig.fromJson(prefs[homeConfigKey(profileId)])
    }

    suspend fun updateHomeConfig(profileId: Long, transform: (HomeConfig) -> HomeConfig) {
        context.dataStore.edit { prefs ->
            val key = homeConfigKey(profileId)
            val next = transform(HomeConfig.fromJson(prefs[key]))
            if (next == HomeConfig()) prefs.remove(key) else prefs[key] = next.toJson().toString()
        }
    }

    // --- Startup: auto-open the last-watched live channel (default OFF) — legacy, now migrated to startupMode ---
    val resumeLastChannel: Flow<Boolean> = context.dataStore.data.map { it[Keys.RESUME_LAST_CHANNEL] ?: false }
    suspend fun setResumeLastChannel(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RESUME_LAST_CHANNEL] = enabled }
    }

    // --- Live TV: remember the last selected category so reopening lands where you left off ---
    val lastLiveCategory: Flow<String> = context.dataStore.data.map { it[Keys.LAST_LIVE_CATEGORY] ?: "" }
    suspend fun setLastLiveCategory(key: String) {
        context.dataStore.edit { it[Keys.LAST_LIVE_CATEGORY] = key }
    }

    // --- Appearance: animation level (perf control for low-end boxes) ---
    val animationLevel: Flow<tv.own.owntv.ui.theme.AnimationLevel> = context.dataStore.data.map { prefs ->
        prefs[Keys.ANIMATION_LEVEL]?.let { runCatching { tv.own.owntv.ui.theme.AnimationLevel.valueOf(it) }.getOrNull() }
            ?: tv.own.owntv.ui.theme.AnimationLevel.FULL
    }

    suspend fun setAnimationLevel(level: tv.own.owntv.ui.theme.AnimationLevel) {
        context.dataStore.edit { it[Keys.ANIMATION_LEVEL] = level.name }
    }

    // --- Weather chip (top bar): show/hide + manual location override for VPN users ---

    /** Show the weather chip in the top bar (default ON). */
    val weatherEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEATHER_ENABLED] ?: true }

    suspend fun setWeatherEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WEATHER_ENABLED] = enabled }
    }

    /**
     * Manual weather location. Blank (default) = auto-detect from public IP. Otherwise a city name
     * (geocoded via Open-Meteo) or a raw "lat,lon" pair. Lets users fix the wrong-city behaviour
     * they see behind a VPN, where IP geolocation resolves to the VPN server's city.
     */
    val weatherLocation: Flow<String> = context.dataStore.data.map { it[Keys.WEATHER_LOCATION] ?: "" }

    suspend fun setWeatherLocation(location: String) {
        context.dataStore.edit { it[Keys.WEATHER_LOCATION] = location.trim() }
    }

    // --- TMDB metadata enrichment (plan §4) ---
    // One provider, three configs. Enrichment is opt-outable via the master toggle; the two advanced
    // fields (own key / self-host URL) override the default caching Worker when set.

    /** Metadata source mode (plan §4.1). Defaults to Provider+TMDB; back-compat: an old boolean master
     *  toggle maps false→Provider, true→Provider+TMDB when no explicit mode is stored yet. */
    val metadataMode: Flow<tv.own.owntv.core.metadata.MetadataMode> = context.dataStore.data.map { p ->
        parseMetadataMode(p)
    }

    private fun parseMetadataMode(p: Preferences): tv.own.owntv.core.metadata.MetadataMode {
        p[Keys.METADATA_MODE]?.let { raw ->
            runCatching { tv.own.owntv.core.metadata.MetadataMode.valueOf(raw) }.getOrNull()?.let { return it }
        }
        // No explicit mode yet — derive from the legacy boolean toggle.
        return if (p[Keys.METADATA_ENABLED] == false) tv.own.owntv.core.metadata.MetadataMode.PROVIDER
        else tv.own.owntv.core.metadata.MetadataMode.PROVIDER_PLUS_TMDB
    }

    suspend fun setMetadataMode(mode: tv.own.owntv.core.metadata.MetadataMode) {
        context.dataStore.edit {
            it[Keys.METADATA_MODE] = mode.name
            it[Keys.METADATA_ENABLED] = mode.enrich // keep legacy key coherent for older readers
        }
    }

    /** Tier 2 — the user's own TMDB v3 API key; blank = don't call TMDB directly. */
    val tmdbApiKey: Flow<String> = context.dataStore.data.map { it[Keys.TMDB_API_KEY] ?: "" }

    suspend fun setTmdbApiKey(key: String) {
        context.dataStore.edit { it[Keys.TMDB_API_KEY] = key.trim() }
    }

    /** Tier 3 — a custom TMDB-shaped metadata server base URL; blank = don't self-host. */
    val metadataServerUrl: Flow<String> = context.dataStore.data.map { it[Keys.METADATA_SERVER_URL] ?: "" }

    suspend fun setMetadataServerUrl(url: String) {
        context.dataStore.edit { it[Keys.METADATA_SERVER_URL] = url.trim() }
    }

    /** Live snapshot of the three metadata settings as one object (consumed by TmdbProvider). */
    val metadataConfigFlow: Flow<tv.own.owntv.core.metadata.MetadataConfig> = context.dataStore.data.map { p ->
        tv.own.owntv.core.metadata.MetadataConfig(
            mode = parseMetadataMode(p),
            tmdbApiKey = p[Keys.TMDB_API_KEY] ?: "",
            customServerUrl = p[Keys.METADATA_SERVER_URL] ?: "",
        )
    }

    /** One-shot read of the current metadata config (used by TmdbProvider per call). */
    suspend fun metadataConfig(): tv.own.owntv.core.metadata.MetadataConfig = metadataConfigFlow.first()

    // --- Catch-up (archive) playback ---

    /** Which timezone to format Xtream timeshift URLs in. Most panels run on the server's local time, which
     *  usually matches the user's region — so **Device** is the default; a manual UTC offset is the fallback. */
    enum class CatchupTimezone { DEVICE, MANUAL }

    /** Manual UTC offset bounds (whole hours), in minutes. */
    val catchupOffsetRangeMinutes: IntRange = -12 * 60..14 * 60

    val catchupTimezone: Flow<CatchupTimezone> = context.dataStore.data.map { prefs ->
        prefs[Keys.CATCHUP_TZ]?.let { runCatching { CatchupTimezone.valueOf(it) }.getOrNull() } ?: CatchupTimezone.DEVICE
    }

    /** Manual mode's offset from UTC, in minutes (0 = UTC, the previous default). */
    val catchupOffsetMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.CATCHUP_OFFSET_MIN] ?: 0 }

    suspend fun setCatchupTimezone(mode: CatchupTimezone) {
        context.dataStore.edit { it[Keys.CATCHUP_TZ] = mode.name }
    }

    suspend fun setCatchupOffsetMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.CATCHUP_OFFSET_MIN] = minutes.coerceIn(catchupOffsetRangeMinutes) }
    }

    /** The timezone catch-up/timeshift URLs are formatted in — device tz, or a manual UTC offset. */
    suspend fun resolveCatchupTimeZone(): java.util.TimeZone = when (catchupTimezone.first()) {
        CatchupTimezone.DEVICE -> java.util.TimeZone.getDefault()
        CatchupTimezone.MANUAL -> java.util.SimpleTimeZone(catchupOffsetMinutes.first() * 60_000, "catchup")
    }

    /** Automatically check GitHub Releases for a newer version shortly after launch. */
    val updateCheckOnStart: Flow<Boolean> = context.dataStore.data.map { it[Keys.UPDATE_CHECK_ON_START] ?: true }

    suspend fun setUpdateCheckOnStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.UPDATE_CHECK_ON_START] = enabled }
    }

    // --- Resume behavior for movies/episodes with a saved position ---

    enum class ResumeMode(val label: String) {
        AUTO("Always resume"), ASK("Ask to resume"), NEVER("Never resume")
    }

    val resumeMode: Flow<ResumeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.RESUME_MODE]?.let { runCatching { ResumeMode.valueOf(it) }.getOrNull() } ?: ResumeMode.ASK
    }

    suspend fun setResumeMode(mode: ResumeMode) {
        context.dataStore.edit { it[Keys.RESUME_MODE] = mode.name }
    }

    // --- List sorting (per browse section) ---

    /** How a browse section's lists are ordered. RATING (highest provider rating first) applies to
     *  Movies/Series only; Live/EPG never select it. */
    enum class SortMode { PLAYLIST, ALPHA, RATING }

    /** All three browse sections (Live/Movies/Series) default to the playlist/provider's own order — the
     *  natural grouping a user expects right after a sync. A–Z is one tap away (toggleSort). */
    val sortLive: Flow<SortMode> = context.dataStore.data.map { parseSort(it[Keys.SORT_LIVE], SortMode.PLAYLIST) }
    val sortMovies: Flow<SortMode> = context.dataStore.data.map { parseSort(it[Keys.SORT_MOVIES], SortMode.PLAYLIST) }
    val sortSeries: Flow<SortMode> = context.dataStore.data.map { parseSort(it[Keys.SORT_SERIES], SortMode.PLAYLIST) }

    suspend fun setSortLive(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_LIVE] = mode.name }
    }

    suspend fun setSortMovies(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_MOVIES] = mode.name }
    }

    suspend fun setSortSeries(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_SERIES] = mode.name }
    }

    private fun parseSort(raw: String?, default: SortMode): SortMode =
        raw?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: default

    /** The TV Guide's own ordering. LIVE_TV mirrors the Live TV sort; CATCHUP floats archive channels up. */
    enum class GuideSort(val label: String) { ALPHA("A–Z"), PROVIDER("Provider"), LIVE_TV("Live TV"), CATCHUP("Catch-up"), FAVORITES("Favorites") }

    /** How Movies & Series browse: the poster wall, or a compact list (more titles at once). */
    enum class VodViewMode(val label: String) { GRID("Grid"), LIST("List") }
    val vodViewMode: Flow<VodViewMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.VOD_VIEW_MODE]?.let { runCatching { VodViewMode.valueOf(it) }.getOrNull() } ?: VodViewMode.GRID
    }
    suspend fun setVodViewMode(mode: VodViewMode) {
        context.dataStore.edit { it[Keys.VOD_VIEW_MODE] = mode.name }
    }

    val sortGuide: Flow<GuideSort> = context.dataStore.data.map { prefs ->
        prefs[Keys.SORT_GUIDE]?.let { runCatching { GuideSort.valueOf(it) }.getOrNull() } ?: GuideSort.LIVE_TV
    }

    suspend fun setSortGuide(mode: GuideSort) {
        context.dataStore.edit { it[Keys.SORT_GUIDE] = mode.name }
    }

    // --- Video Player Settings ---

    /** Hardware decoding (mpv hwdec auto-safe). Off = force software decoding for tricky streams. */
    val hwDecoding: Flow<Boolean> = context.dataStore.data.map { it[Keys.HW_DECODING] ?: true }

    suspend fun setHwDecoding(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HW_DECODING] = enabled }
    }

    /** Preferred engine for Movies & Series (VOD). Off (default) = mpv first with an automatic ExoPlayer
     *  fallback; on = ExoPlayer first with an automatic mpv fallback. mpv is the default because it has
     *  the wider codec support (DTS/TrueHD audio, odd containers) and the A/V-sync nudge; ExoPlayer-first
     *  is for devices/providers where mpv's path can't open streams that ExoPlayer plays fine. */
    val vodPreferExo: Flow<Boolean> = context.dataStore.data.map { it[Keys.VOD_PREFER_EXO] ?: false }

    suspend fun setVodPreferExo(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOD_PREFER_EXO] = enabled }
    }

    /** Surround sound (**off by default — opt-in**). Most users are on TV speakers / 2.0 soundbars, and
     *  forcing a multichannel-LPCM path exposes flaky vendor audio HALs / lying HDMI-ARC chips that claim
     *  5.1 then mis-play it (drained 2× → "fast video, no sound", #25). So default stereo for stability;
     *  users with a real 5.1/7.1 receiver turn this on. On: mpv decodes Dolby/DTS to multichannel LPCM (the
     *  sink picks the layout), with a runaway-detector that auto-falls-back to stereo on a broken output. We
     *  never bitstream/passthrough (its AudioTrack reports no clock and stutters video to a slideshow).
     *  Second, subtler failure mode (confirmed in the field): even when multichannel LPCM plays correctly,
     *  the wider HDMI/ARC buffer adds latency the TV/soundbar doesn't report back, so audio lags video
     *  (lip-sync drift) on VODs. Stereo's small, well-reported buffer stays locked. Hence: default OFF. */
    val surroundSound: Flow<Boolean> = context.dataStore.data.map { it[Keys.SURROUND_SOUND] ?: false }

    suspend fun setSurroundSound(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SURROUND_SOUND] = enabled }
    }

    /** Auto-play the next episode (and roll into the next season) when one finishes. On by default. */
    val autoPlayNext: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_PLAY_NEXT] ?: true }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PLAY_NEXT] = enabled }
    }

    /** Default zoom/aspect mode applied when playback starts (a [tv.own.owntv.player.ZoomMode] name). */
    val defaultZoom: Flow<String> = context.dataStore.data.map { it[Keys.DEFAULT_ZOOM] ?: "FIT" }

    suspend fun setDefaultZoom(name: String) {
        context.dataStore.edit { it[Keys.DEFAULT_ZOOM] = name }
    }

    /** Subtitle scale multiplier (mpv sub-scale); 1.0 = normal. */
    val subtitleScale: Flow<Float> = context.dataStore.data.map { it[Keys.SUB_SCALE] ?: 1.0f }

    suspend fun setSubtitleScale(scale: Float) {
        context.dataStore.edit { it[Keys.SUB_SCALE] = scale }
    }

    /** Audio sync offset in milliseconds (mpv audio-delay); +ve delays audio. */
    val audioDelayMs: Flow<Int> = context.dataStore.data.map { it[Keys.AUDIO_DELAY_MS] ?: 0 }

    suspend fun setAudioDelayMs(ms: Int) {
        context.dataStore.edit { it[Keys.AUDIO_DELAY_MS] = ms }
    }

    /** Preferred audio language (ISO code, mpv alang); blank = no preference. */
    val preferredAudioLang: Flow<String> = context.dataStore.data.map { it[Keys.PREF_AUDIO_LANG] ?: "" }

    suspend fun setPreferredAudioLang(lang: String) {
        context.dataStore.edit { it[Keys.PREF_AUDIO_LANG] = lang }
    }

    /** Preferred subtitle language (ISO code, mpv slang); blank = no preference. */
    val preferredSubLang: Flow<String> = context.dataStore.data.map { it[Keys.PREF_SUB_LANG] ?: "" }

    suspend fun setPreferredSubLang(lang: String) {
        context.dataStore.edit { it[Keys.PREF_SUB_LANG] = lang }
    }

    // --- Per-source auto-refresh (Off / Startup / staleness threshold) ---
    // Stored as a JSON map { "<sourceId>": "<EnumName>" } in the owntv_settings DataStore — migration-safe
    // (Room uses destructive migrations, so anything that must survive a schema bump lives here). Reuses the
    // existing lastSyncAt columns (SourceEntity.lastSyncAt for playlists, EpgSource.lastSyncAt for EPG) as the
    // "last successful sync" timestamp; nothing new is stored for that.

    /** Per-source playlist auto-refresh selection. Missing ids default to [PlaylistAutoRefresh.OFF]. */
    val playlistAutoRefresh: Flow<Map<Long, PlaylistAutoRefresh>> =
        context.dataStore.data.map { prefs -> parseRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH]) { PlaylistAutoRefresh.valueOf(it) } }

    /** Per-source EPG auto-refresh selection. Missing ids default to [EpgAutoRefresh.OFF]. */
    val epgAutoRefresh: Flow<Map<Long, EpgAutoRefresh>> =
        context.dataStore.data.map { prefs -> parseRefreshMap(prefs[Keys.EPG_AUTO_REFRESH]) { EpgAutoRefresh.valueOf(it) } }

    suspend fun setPlaylistAutoRefresh(sourceId: Long, mode: PlaylistAutoRefresh) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PLAYLIST_AUTO_REFRESH] = writeRefreshMap(readRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH]), sourceId, mode.name)
        }
    }

    suspend fun setEpgAutoRefresh(sourceId: Long, mode: EpgAutoRefresh) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_AUTO_REFRESH] = writeRefreshMap(readRefreshMap(prefs[Keys.EPG_AUTO_REFRESH]), sourceId, mode.name)
        }
    }

    /**
     * One-time migration of the legacy binary `refresh_source_ids` set → per-source `STARTUP` entries.
     * Idempotent (guarded by [Keys.REFRESH_MIGRATED]) and non-overwriting: if the new
     * [playlistAutoRefresh] map is already non-empty (user picked a mode in the new UI, or a prior migration
     * ran), we only flip the flag and return — never clobbering existing selections.
     */
    suspend fun migrateLegacyRefreshFlags() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.REFRESH_MIGRATED] == true) return@edit
            val existing = readRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH])
            val legacyIds = prefs[Keys.REFRESH_SOURCE_IDS].orEmpty()
            // Only migrate into an empty map — never overwrite selections already made in the new UI.
            if (existing.isEmpty() && legacyIds.isNotEmpty()) {
                val migrated = legacyIds.associate { it to PlaylistAutoRefresh.STARTUP.name }
                prefs[Keys.PLAYLIST_AUTO_REFRESH] =
                    org.json.JSONObject(migrated).toString()
            }
            prefs[Keys.REFRESH_MIGRATED] = true
        }
    }

    private inline fun <reified E : Enum<E>> parseRefreshMap(raw: String?, valueOf: (String) -> E): Map<Long, E> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Long, E>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toLongOrNull() ?: continue
            val name = obj.optString(key)
            val mode = runCatching { valueOf(name) }.getOrNull() ?: continue
            out[id] = mode
        }
        return out
    }

    private fun readRefreshMap(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) return LinkedHashMap()
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return LinkedHashMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key)
        }
        return out
    }

    private fun writeRefreshMap(map: MutableMap<String, String>, sourceId: Long, value: String): String {
        map[sourceId.toString()] = value
        return org.json.JSONObject(map.toMap()).toString()
    }

    /** Whether focusing a channel auto-plays it in the Live preview pane. */
    val livePreviewEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.LIVE_PREVIEW] ?: true }

    suspend fun setLivePreviewEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_PREVIEW] = enabled }
    }

    /** Whether the Live preview plays audio (off by default so browsing stays quiet). */
    val livePreviewAudio: Flow<Boolean> = context.dataStore.data.map { it[Keys.LIVE_PREVIEW_AUDIO] ?: false }

    suspend fun setLivePreviewAudio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_PREVIEW_AUDIO] = enabled }
    }

    /** Use HDR output when the video and display support it. */
    val hdrEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.HDR_ENABLED] ?: true }

    suspend fun setHdrEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HDR_ENABLED] = enabled }
    }

    /** Mirror continue-watching rows into Android TV home surfaces. */
    val androidTvHomeEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.ANDROID_TV_HOME] ?: true }

    suspend fun setAndroidTvHomeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ANDROID_TV_HOME] = enabled }
    }

    /** The source shown as "active" in the sidebar; -1 = none chosen (fall back to the first source). */
    val defaultSourceId: Flow<Long> = context.dataStore.data.map { it[Keys.DEFAULT_SOURCE] ?: -1L }

    suspend fun setDefaultSource(id: Long) {
        context.dataStore.edit { it[Keys.DEFAULT_SOURCE] = id }
    }

    /** User-chosen download base folder; blank = app-specific storage. */
    val downloadRoot: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_ROOT] ?: "" }

    suspend fun setDownloadRoot(path: String) {
        context.dataStore.edit { it[Keys.DOWNLOAD_ROOT] = path }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    val uiZoomPercent: Flow<Int> = context.dataStore.data.map { prefs ->
        UiZoom.clamp(prefs[Keys.UI_ZOOM_PCT] ?: UiZoom.DEFAULT)
    }

    suspend fun setUiZoomPercent(percent: Int) {
        context.dataStore.edit { it[Keys.UI_ZOOM_PCT] = UiZoom.clamp(percent) }
    }

    val accent: Flow<AccentColor> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACCENT]?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
            ?: AccentColor.TEAL
    }

    /** Picking a preset clears any custom accent so the preset takes effect. */
    suspend fun setAccent(accent: AccentColor) {
        context.dataStore.edit {
            it[Keys.ACCENT] = accent.name
            it[Keys.ACCENT_CUSTOM] = ""
        }
    }

    /** Custom accent as a hex string ("#52DBC8"); blank = use the [accent] preset. */
    val customAccent: Flow<String> = context.dataStore.data.map { it[Keys.ACCENT_CUSTOM] ?: "" }

    suspend fun setCustomAccent(hex: String) {
        context.dataStore.edit { it[Keys.ACCENT_CUSTOM] = hex.trim() }
    }

    /** Avatar for the current (placeholder) profile until real profiles arrive in the wizard. */
    val avatarId: Flow<Int> = context.dataStore.data.map { it[Keys.AVATAR_ID] ?: 0 }

    suspend fun setAvatarId(id: Int) {
        context.dataStore.edit { it[Keys.AVATAR_ID] = id }
    }

    /** Active profile id; -1 means first-run / setup not yet completed. */
    val activeProfileId: Flow<Long> = context.dataStore.data.map { it[Keys.ACTIVE_PROFILE] ?: -1L }

    suspend fun setActiveProfile(id: Long) {
        context.dataStore.edit { it[Keys.ACTIVE_PROFILE] = id }
    }

    // --- Global proxy (Approach 1 — one app-wide HTTP proxy) ---
    // Covers all OkHttp traffic (playlist/API/EPG/images/downloads/updates/weather + ExoPlayer) and mpv
    // playback via its http-proxy option. Per-source overrides and SOCKS are future work; the proxy
    // password is intentionally NOT part of settings backup/export — see extras/PROXY_SUPPORT_PLAN.md.

    /** Live snapshot of the proxy settings as a single object (consumed by ProxyConfigHolder). */
    val proxyConfig: Flow<tv.own.owntv.core.network.ProxyConfig> = context.dataStore.data.map { p ->
        tv.own.owntv.core.network.ProxyConfig(
            enabled = p[Keys.PROXY_ENABLED] ?: false,
            host = p[Keys.PROXY_HOST] ?: "",
            port = p[Keys.PROXY_PORT] ?: 0,
            username = p[Keys.PROXY_USER] ?: "",
            password = p[Keys.PROXY_PASS] ?: "",
        )
    }

    suspend fun setProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PROXY_ENABLED] = enabled }
    }

    /** Persist the proxy form in one write (enabled + host/port/user/pass). Blank user/pass = no auth.
     *  Port is clamped to a valid range; 0 means "unset". */
    suspend fun saveProxy(enabled: Boolean, host: String, port: Int, username: String, password: String) {
        context.dataStore.edit {
            it[Keys.PROXY_ENABLED] = enabled
            it[Keys.PROXY_HOST] = host.trim()
            it[Keys.PROXY_PORT] = port.coerceIn(0, 65535)
            it[Keys.PROXY_USER] = username.trim()
            it[Keys.PROXY_PASS] = password
        }
    }

    // --- Backup / restore of pure UI/player preferences (device-agnostic) ---
    // Deliberately EXCLUDES the download folder (a device-specific path) and the profile/source-coupled
    // keys (active profile, default source, refresh-on-startup) — those ride with the sources backup.

    private val backupStringKeys = listOf(
        Keys.THEME_MODE, Keys.ACCENT, Keys.ACCENT_CUSTOM, Keys.DEFAULT_ZOOM,
        Keys.PREF_AUDIO_LANG, Keys.PREF_SUB_LANG, Keys.SORT_LIVE, Keys.SORT_GUIDE, Keys.SORT_MOVIES,
        Keys.SORT_SERIES, Keys.RESUME_MODE, Keys.CATCHUP_TZ, Keys.ANIMATION_LEVEL, Keys.VOD_VIEW_MODE,
        Keys.WEATHER_LOCATION,
        // Global proxy — non-secret fields only. The proxy password (Keys.PROXY_PASS) is NEVER part of
        // this whitelist; it is handled separately by BackupManager (encrypted or omitted).
        Keys.PROXY_HOST, Keys.PROXY_USER,
        // TMDB metadata: source mode + self-host URL. The user's own TMDB API key (Keys.TMDB_API_KEY) is a
        // secret and is deliberately NOT backed up in plaintext (same policy as the proxy password).
        Keys.METADATA_SERVER_URL, Keys.METADATA_MODE,
        // Download folder. Backed up so a same-device reinstall keeps the chosen folder; on a different
        // device a path that no longer exists is harmless — StorageAccess.resolveRoot falls back to app
        // storage, so a stale restore never breaks downloads.
        Keys.DOWNLOAD_ROOT,
    )
    private val backupIntKeys = listOf(Keys.UI_ZOOM_PCT, Keys.AUDIO_DELAY_MS, Keys.CATCHUP_OFFSET_MIN, Keys.PROXY_PORT)
    private val backupBoolKeys = listOf(
        Keys.LIVE_PREVIEW, Keys.LIVE_PREVIEW_AUDIO, Keys.HDR_ENABLED, Keys.ANDROID_TV_HOME, Keys.HW_DECODING,
        Keys.VOD_PREFER_EXO, Keys.UPDATE_CHECK_ON_START, Keys.SURROUND_SOUND, Keys.AUTO_PLAY_NEXT, Keys.PROXY_ENABLED,
        Keys.WEATHER_ENABLED, Keys.RESUME_LAST_CHANNEL, Keys.METADATA_ENABLED,
    )
    private val backupFloatKeys = listOf(Keys.SUB_SCALE)

    suspend fun exportSettings(): org.json.JSONObject {
        val p = context.dataStore.data.first()
        return org.json.JSONObject().apply {
            backupStringKeys.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupIntKeys.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupBoolKeys.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupFloatKeys.forEach { k -> p[k]?.let { put(k.name, it.toDouble()) } }
        }
    }

    suspend fun importSettings(o: org.json.JSONObject) {
        context.dataStore.edit { prefs ->
            backupStringKeys.forEach { k -> if (o.has(k.name)) prefs[k] = o.getString(k.name) }
            backupIntKeys.forEach { k -> if (o.has(k.name)) prefs[k] = o.getInt(k.name) }
            backupBoolKeys.forEach { k -> if (o.has(k.name)) prefs[k] = o.getBoolean(k.name) }
            backupFloatKeys.forEach { k -> if (o.has(k.name)) prefs[k] = o.getDouble(k.name).toFloat() }
        }
    }

    // --- Backup: per-profile startup landing (dynamic "startup_mode_<id>" keys) ---

    /** Exports all per-profile startup-mode keys as { "<profileId>": "<MODE>" }. */
    suspend fun exportStartupModes(): org.json.JSONObject {
        val prefix = "startup_mode_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String) {
                out.put(k.name.removePrefix(prefix), v)
            }
        }
        return out
    }

    /** Exports all per-profile Home config blobs as { "<profileId>": { ... } }. */
    suspend fun exportHomeConfigs(): org.json.JSONObject {
        val prefix = "home_config_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String) {
                val blob = runCatching { org.json.JSONObject(v) }.getOrNull() ?: return@forEach
                out.put(k.name.removePrefix(prefix), blob)
            }
        }
        return out
    }

    /** Restores startup modes only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importStartupModes(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val mode = o.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                if (runCatching { StartupMode.valueOf(mode) }.isSuccess) {
                    prefs[stringPreferencesKey("startup_mode_$pid")] = mode
                }
            }
        }
    }

    /** Restores Home configs only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importHomeConfigs(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val blob = o.optJSONObject(key) ?: return@forEach
                prefs[homeConfigKey(pid)] = blob.toString()
            }
        }
    }

    // --- Backup: per-source auto-refresh maps (ride with the SOURCES section, since source/EPG ids
    //     are preserved on restore). Exported as the raw { "<id>": "<EnumName>" } JSON maps. ---

    /** Exports the per-source playlist auto-refresh map as { "<sourceId>": "<mode>" }. */
    suspend fun exportPlaylistAutoRefresh(): org.json.JSONObject =
        context.dataStore.data.first()[Keys.PLAYLIST_AUTO_REFRESH]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()

    /** Exports the per-EPG-source auto-refresh map as { "<epgSourceId>": "<mode>" }. */
    suspend fun exportEpgAutoRefresh(): org.json.JSONObject =
        context.dataStore.data.first()[Keys.EPG_AUTO_REFRESH]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()

    /**
     * Restores the playlist auto-refresh map. Ids not in [existingSourceIds] are dropped; unknown
     * enum values fall back to OFF. Replaces the whole map (SOURCES restore wipes+recreates sources,
     * so pre-restore selections refer to deleted ids). Also marks the legacy refresh migration done
     * so it can never clobber the restored selections.
     */
    suspend fun importPlaylistAutoRefresh(o: org.json.JSONObject, existingSourceIds: Set<Long>) {
        val cleaned = sanitizeRefreshMap(o, existingSourceIds) { runCatching { PlaylistAutoRefresh.valueOf(it) }.getOrDefault(PlaylistAutoRefresh.OFF).name }
        context.dataStore.edit { prefs ->
            prefs[Keys.PLAYLIST_AUTO_REFRESH] = org.json.JSONObject(cleaned.toMap()).toString()
            prefs[Keys.REFRESH_MIGRATED] = true
        }
    }

    /** Restores the EPG auto-refresh map; same semantics as [importPlaylistAutoRefresh]. */
    suspend fun importEpgAutoRefresh(o: org.json.JSONObject, existingEpgSourceIds: Set<Long>) {
        val cleaned = sanitizeRefreshMap(o, existingEpgSourceIds) { runCatching { EpgAutoRefresh.valueOf(it) }.getOrDefault(EpgAutoRefresh.OFF).name }
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_AUTO_REFRESH] = org.json.JSONObject(cleaned.toMap()).toString()
        }
    }

    private inline fun sanitizeRefreshMap(
        o: org.json.JSONObject,
        existingIds: Set<Long>,
        sanitize: (String) -> String,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { key ->
            val id = key.toLongOrNull() ?: return@forEach
            if (id !in existingIds) return@forEach
            out[key] = sanitize(o.optString(key))
        }
        return out
    }

    // --- Backup: default source (SOURCES section — ids are preserved on restore) ---

    /** Currently selected default source id, or null when none chosen. */
    suspend fun currentDefaultSourceId(): Long? =
        context.dataStore.data.first()[Keys.DEFAULT_SOURCE]?.takeIf { it > 0 }

    /** Restores the default source only when that id survived the restore. */
    suspend fun importDefaultSource(id: Long, existingSourceIds: Set<Long>) {
        if (id in existingSourceIds) setDefaultSource(id)
    }

    // --- Backup: proxy password (handled out-of-band by BackupManager: encrypted or omitted) ---

    /** Current proxy password, for the backup layer to encrypt. Never logged. */
    suspend fun currentProxyPassword(): String = context.dataStore.data.first()[Keys.PROXY_PASS] ?: ""

    /** Sets only the proxy password (used on restore once decrypted). Blank clears it. */
    suspend fun setProxyPassword(password: String) {
        context.dataStore.edit { it[Keys.PROXY_PASS] = password }
    }
}
