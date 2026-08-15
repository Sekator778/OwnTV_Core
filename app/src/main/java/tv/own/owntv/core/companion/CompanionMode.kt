package tv.own.owntv.core.companion

/**
 * What the companion HTTP server is currently serving:
 *  - [ADD_SOURCE] — the mobile add-source form (Xtream / M3U / Stalker), the original Remote flow;
 *  - [BACKUP_RESTORE] — an upload page the phone uses to send an OwnTV backup JSON to the TV;
 *  - [BACKUP_DOWNLOAD] — a download page the phone uses to fetch a backup JSON the TV just exported;
 *  - [IMAGE_UPLOAD] — an upload page the phone uses to send a background image to the TV;
 *  - [TMDB_KEY] — a one-field page the phone uses to send a personal TMDB API key to the TV.
 *
 * One server, one PIN gate; the mode only changes which page is served and which endpoint is accepted.
 */
enum class CompanionMode { ADD_SOURCE, BACKUP_RESTORE, BACKUP_DOWNLOAD, IMAGE_UPLOAD, TMDB_KEY, TMDB_CONFIG, OPEN_SUBTITLES_CONFIG }

data class CompanionServiceConfig(val apiKey: String, val serverUrl: String)
