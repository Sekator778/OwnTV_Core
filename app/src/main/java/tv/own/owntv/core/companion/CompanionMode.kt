package tv.own.owntv.core.companion

/**
 * What the companion HTTP server is currently serving:
 *  - [ADD_SOURCE] — the mobile add-source form (Xtream / M3U / Stalker), the original Remote flow;
 *  - [BACKUP_RESTORE] — an upload page the remote device uses to send an OwnTV backup JSON to the TV;
 *  - [BACKUP_DOWNLOAD] — a download page the remote device uses to fetch a backup the TV just exported;
 *  - [IMAGE_UPLOAD] — an upload page the remote device uses to send a background image to the TV;
 *  - [TMDB_KEY] — a one-field page the remote device uses to send a personal TMDB API key to the TV.
 *
 * "Remote device" is any browser on the same Wi-Fi — a phone, a tablet or a desktop with the URL
 * typed in — which is why nothing here is named after a phone.
 *
 * One server, one PIN gate; the mode only changes which page is served and which endpoint is accepted.
 */
enum class CompanionMode { ADD_SOURCE, BACKUP_RESTORE, BACKUP_DOWNLOAD, IMAGE_UPLOAD, TMDB_KEY, TMDB_CONFIG, OPEN_SUBTITLES_CONFIG }

/**
 * A service setup handed over from the remote browser.
 *
 * [username]/[password] are only ever populated in [CompanionMode.OPEN_SUBTITLES_CONFIG] — TMDB has
 * no account to sign in to, and the server drops both fields in any other mode.
 */
data class CompanionServiceConfig(
    val apiKey: String,
    val serverUrl: String,
    val username: String = "",
    val password: String = "",
)
