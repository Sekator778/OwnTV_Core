package tv.own.owntv.core.companion

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tv.own.owntv.core.model.SourceType

/**
 * Tiny embedded HTTP listener behind the Remote add-source flow. It serves a mobile-friendly,
 * OwnTV-themed form (Xtream / M3U / Stalker) that any device on the same Wi-Fi can open.
 *
 * ## Security model
 * The QR encodes only the server URL — never the PIN. Opening it shows a PIN-entry gate; the visitor
 * types the 6-digit PIN shown on the TV. Only then is the form served (with the PIN baked into its
 * submit endpoints). Direct POSTs to `/xtream|/m3u|/stalker` must also carry the PIN (`?pin=` or the
 * `X-Companion-Pin` header) or receive 401. So a stray device on the network cannot push a source.
 *
 * The phone only fills the form; it never starts the import. Submitted details are surfaced via
 * [start]'s onPayload callback, which the host uses to pre-fill Add Source on the TV.
 *
 * Everything HTTP happens off the main thread on [Dispatchers.IO]. Passwords/MAC are never logged.
 */
class CompanionHttpServer {

    /**
     * The server's **own** slice of the IO pool (C3). Every accepted socket used to be handled on the
     * shared `Dispatchers.IO`, where each one holds a thread for up to its `soTimeout` (10 s, or 30 s
     * for uploads) — so a connection flood, or just a browser pre-connecting aggressively, could park
     * the whole pool and stall the app's own syncs, downloads and image loading. A limited view caps
     * the blast radius at [MAX_PARALLELISM] threads. It's a view of `Dispatchers.IO`, not a pool of
     * its own, so there is nothing extra to release in [close].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val running = AtomicBoolean(false)

    /** In-flight connections (C3). Beyond the cap a socket is closed at once rather than queued. */
    private val inFlight = java.util.concurrent.Semaphore(MAX_IN_FLIGHT)

    /**
     * Consecutive wrong PINs this session (C2). A 6-digit PIN is a million possibilities, which a
     * script on a LAN exhausts in minutes; the constant-time compare stops timing leakage but says
     * nothing about volume. Reset by any correct PIN.
     */
    private val failedPins = java.util.concurrent.atomic.AtomicInteger(0)

    /** Fired when [MAX_PIN_ATTEMPTS] is reached and the server shuts itself down. */
    @Volatile private var onLocked: () -> Unit = {}

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pin: String = ""

    /** Bundled Lora TTF served at `/lora.ttf` so the web form matches the app's popup font offline. */
    @Volatile private var fontBytes: ByteArray? = null

    /** Which page/endpoint set is live for the current session (add-source vs. backup upload). */
    @Volatile private var mode: CompanionMode = CompanionMode.ADD_SOURCE

    /** Session callbacks — set on [start], only the one matching [mode] ever fires. */
    @Volatile private var onPayload: (CompanionPayload) -> Unit = {}
    @Volatile private var onBackup: (String) -> Unit = {}
    @Volatile private var onImage: (bytes: ByteArray, extension: String) -> Unit = { _, _ -> }

    /** The backup file served at `/backup.json` in [CompanionMode.BACKUP_DOWNLOAD] mode. */
    @Volatile private var downloadFile: File? = null

    /**
     * Bind [port] and start accepting. [pin] gates the pages and POST endpoints. [fontBytes], when
     * provided, is served at `/lora.ttf`. [mode] selects the served page (add-source form or backup
     * upload); [onPayload] receives an add-source submission, [onBackup] the raw JSON of an uploaded
     * backup. Returns the LAN URLs to show on the TV. Throws on bind failure (the caller maps that to
     * [CompanionServerState.Failed]).
     */
    fun start(
        port: Int,
        pin: String,
        fontBytes: ByteArray?,
        mode: CompanionMode = CompanionMode.ADD_SOURCE,
        onPayload: (CompanionPayload) -> Unit = {},
        onBackup: (String) -> Unit = {},
        onImage: (bytes: ByteArray, extension: String) -> Unit = { _, _ -> },
        downloadFile: File? = null,
        onLocked: () -> Unit = {},
    ): List<String> {
        stop()
        this.pin = pin
        this.onLocked = onLocked
        failedPins.set(0)
        this.fontBytes = fontBytes
        this.mode = mode
        this.onPayload = onPayload
        this.onBackup = onBackup
        this.onImage = onImage
        this.downloadFile = downloadFile
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running.set(true)
        scope.launch { acceptLoop(socket) }
        return CompanionLink.lanUrls(port)
    }

    fun stop() {
        running.set(false)
        pin = ""
        onPayload = {}
        onBackup = {}
        onImage = { _, _ -> }
        onLocked = {}
        downloadFile = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** Permanent teardown — cancels the I/O scope. Call when the owning singleton is disposed. */
    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        try {
            while (running.get() && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "Companion accept failed", e)
                    break
                }
                // C3: shed rather than queue. A queued connection still holds a socket and would be
                // served long after the client gave up; closing it immediately keeps the cap honest.
                if (!inFlight.tryAcquire()) {
                    Log.w(TAG, "Companion connection refused — $MAX_IN_FLIGHT already in flight")
                    runCatching { client.close() }
                    continue
                }
                scope.launch {
                    try {
                        handleClient(client)
                    } finally {
                        inFlight.release()
                    }
                }
            }
        } finally {
            stop()
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            // Backup/image uploads can be several MB over Wi-Fi; give them more headroom than a tiny form post.
            socket.soTimeout = if (mode == CompanionMode.BACKUP_RESTORE || mode == CompanionMode.IMAGE_UPLOAD) 30_000 else 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return sendText(socket, 400, "Bad request")
            val parts = requestLine.split(' ')
            if (parts.size < 3) return sendText(socket, 400, "Bad request")

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")
            val queryPin = parseQuery(query)["pin"].orEmpty()

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            // Font asset for the web form (no PIN needed — it is only a font).
            if (method == "GET" && path == "/lora.ttf") {
                val bytes = fontBytes
                return if (bytes != null) sendBytes(socket, 200, "font/ttf", bytes) else sendText(socket, 404, "Not found")
            }

            // Landing: a correct PIN in the query (e.g. the "send another" link) skips straight to the
            // page for the current mode; otherwise show the PIN gate.
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                if (pinOk(queryPin)) {
                    pinAccepted()
                    return sendHtml(socket, 200, authedPage())
                }
                // An empty PIN is just someone opening the link, not a guess — only count real ones.
                if (queryPin.isNotBlank() && pinRejected()) return sendText(socket, 403, LOCKOUT_MESSAGE)
                return sendHtml(
                    socket,
                    200,
                    CompanionHtml.pinPage(if (queryPin.isNotBlank()) "That PIN did not match — try again." else null),
                )
            }

            // PIN submission from the gate.
            if (method == "POST" && path == "/") {
                val body = readBody(input, headers, maxBodyBytes(path)) ?: return sendText(socket, 413, "Body too large")
                val submitted = parseQuery(body)["pin"].orEmpty()
                if (pinOk(submitted)) {
                    pinAccepted()
                    return sendHtml(socket, 200, authedPage())
                }
                if (pinRejected()) return sendText(socket, 403, LOCKOUT_MESSAGE)
                return sendHtml(socket, 200, CompanionHtml.pinPage("That PIN did not match — try again."))
            }

            // Backup download (BACKUP_DOWNLOAD mode) — PIN required, streams the exported container.
            // `/backup.json` stays routed here: the old path costs nothing to keep and a phone that
            // bookmarked it still works. What it serves is whatever export produced — a `.own` file.
            if (method == "GET" && (path == "/backup.own" || path == "/backup.json")) {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requirePin(queryPin.ifBlank { headerPin })) return sendText(socket, 401, "Unauthorized")
                val file = downloadFile
                val bytes = file?.takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }
                    ?: return sendText(socket, 404, "No backup available")
                return sendDownload(socket, bytes, file.name)
            }

            // Backup upload (BACKUP_RESTORE mode) — PIN required, JSON body is the backup file.
            if (method == "POST" && path == "/backup") {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requirePin(queryPin.ifBlank { headerPin })) return sendText(socket, 401, "Unauthorized")
                val body = readBody(input, headers, maxBodyBytes(path)) ?: return sendText(socket, 413, "Backup too large")
                if (body.isBlank()) return sendText(socket, 400, "Empty backup")
                onBackup(body)
                return sendHtml(socket, 200, CompanionHtml.backupSentPage(pin))
            }

            // Background-image upload (IMAGE_UPLOAD mode) — PIN required. Body is a base64 data-URL
            // (`data:image/jpeg;base64,...`) produced by the web page's FileReader; decoding it here
            // keeps binary handling out of the socket path.
            if (method == "POST" && path == "/background") {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requirePin(queryPin.ifBlank { headerPin })) return sendText(socket, 401, "Unauthorized")
                val body = readBody(input, headers, maxBodyBytes(path)) ?: return sendText(socket, 413, "Image too large")
                val decoded = decodeImageDataUrl(body) ?: return sendText(socket, 400, "Not a valid image upload")
                onImage(decoded.first, decoded.second)
                return sendHtml(socket, 200, CompanionHtml.imageSentPage(pin))
            }

            // Source submissions — PIN required (query or header), else 401.
            if (method == "POST" && (path == "/xtream" || path == "/m3u" || path == "/stalker")) {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requirePin(queryPin.ifBlank { headerPin })) return sendText(socket, 401, "Unauthorized")
                val fallback = when (path) {
                    "/stalker" -> SourceType.STALKER
                    "/m3u" -> SourceType.M3U
                    else -> SourceType.XTREAM
                }
                val body = readBody(input, headers, maxBodyBytes(path)) ?: return sendText(socket, 413, "Body too large")
                val payload = parsePayload(headers["content-type"], body, fallback)
                    ?: return sendText(socket, 400, "Missing required fields")
                onPayload(payload)
                return sendHtml(socket, 200, CompanionHtml.savedPage(payload, pin))
            }

            sendText(socket, 404, "Not found")
        }
    }

    private fun pinOk(candidate: String): Boolean = pin.isNotBlank() && pinEquals(candidate, pin)

    /**
     * PIN check for the direct POST endpoints, with the C2 attempt counting applied. Returns true
     * when the caller may proceed.
     */
    private suspend fun requirePin(candidate: String): Boolean {
        if (pinOk(candidate)) {
            pinAccepted()
            return true
        }
        pinRejected()
        return false
    }

    /** A correct PIN clears the strike count — the limit is on *consecutive* failures. */
    private fun pinAccepted() {
        failedPins.set(0)
    }

    /**
     * Records a wrong PIN. Returns true once the session is locked out.
     *
     * The delay is **fixed**, not proportional to the attempt number: a variable delay would leak a
     * timing signal, which is exactly what `pinEquals` exists to avoid. On the cap the server stops
     * itself — reopening the screen on the TV mints a fresh PIN, so a lockout costs an attacker a
     * complete restart and the legitimate user one button press.
     */
    private suspend fun pinRejected(): Boolean {
        val attempts = failedPins.incrementAndGet()
        kotlinx.coroutines.delay(FAILED_PIN_DELAY_MS)
        if (attempts < MAX_PIN_ATTEMPTS) return false
        Log.w(TAG, "Companion link locked after $attempts incorrect PIN attempts")
        onLocked()
        stop()
        return true
    }

    /** The page served after a valid PIN, for the current [mode]. */
    private fun authedPage(): String = when (mode) {
        CompanionMode.ADD_SOURCE -> CompanionHtml.formPage(pin)
        CompanionMode.BACKUP_RESTORE -> CompanionHtml.backupUploadPage(pin)
        CompanionMode.BACKUP_DOWNLOAD -> CompanionHtml.backupDownloadPage(pin)
        CompanionMode.IMAGE_UPLOAD -> CompanionHtml.imageUploadPage(pin)
    }

    /**
     * Decode a `data:image/<subtype>;base64,<payload>` data-URL into raw bytes + a file extension,
     * or null when the body isn't one (wrong prefix, non-image mime, or bad base64).
     */
    private fun decodeImageDataUrl(body: String): Pair<ByteArray, String>? {
        val match = Regex("^data:image/([a-zA-Z0-9.+-]+);base64,", RegexOption.IGNORE_CASE).find(body) ?: return null
        val ext = when (match.groupValues[1].lowercase()) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "bmp" -> "bmp"
            else -> return null // keep the accepted set in lockstep with the TV-side picker's extensions
        }
        val bytes = runCatching {
            android.util.Base64.decode(body.substring(match.value.length), android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        return if (bytes.isEmpty()) null else bytes to ext
    }

    /**
     * How many body bytes this endpoint may accept. Uploads (a backup JSON, a base64 background
     * image) legitimately run to megabytes; PIN posts and source forms are a few hundred bytes, so
     * they get the small cap — the PIN gate is reachable *before* authentication and must not let an
     * unauthenticated client on the LAN size an allocation for us.
     */
    internal fun maxBodyBytes(path: String): Int = when {
        path == "/backup" || path == "/background" -> UPLOAD_BODY_LIMIT
        else -> FORM_BODY_LIMIT
    }

    /**
     * Read the request body, bounded by [limit]. Returns null when the body exceeds the cap (the
     * caller answers 413) — `Content-Length` is a client-supplied hint that is checked against the
     * cap but never used to pre-allocate, so a bogus header costs nothing.
     */
    internal fun readBody(input: BufferedInputStream, headers: Map<String, String>, limit: Int): String? {
        val declared = headers["content-length"]?.toLongOrNull() ?: 0L
        if (declared > limit) return null
        if (declared == 0L) return ""
        val buffer = ByteArrayOutputStream(declared.coerceAtMost(INITIAL_BODY_BUFFER.toLong()).toInt())
        val chunk = ByteArray(BODY_CHUNK)
        var total = 0L
        while (total < declared) {
            val want = minOf(chunk.size.toLong(), declared - total).toInt()
            val read = input.read(chunk, 0, want)
            if (read <= 0) break
            total += read
            if (total > limit) return null // defensive: a body longer than it declared
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * Parse a submitted body into a [CompanionPayload], or null if required fields are missing.
     * Public so tests exercise it without a socket. Accepts form-encoded and JSON bodies; field names
     * tolerate camelCase / snake_case / common synonyms.
     */
    fun parsePayload(contentType: String?, bodyText: String, fallbackType: SourceType): CompanionPayload? {
        val isJson = contentType?.contains("application/json", ignoreCase = true) == true ||
            bodyText.trimStart().startsWith("{")
        val fields = if (isJson) {
            // A malformed JSON body (or a JSON-less test env) must not crash the accept thread.
            runCatching { parseJsonFields(bodyText) }.getOrDefault(emptyMap())
        } else {
            parseQuery(bodyText)
        }

        val rawType = pick(fields, "type", "sourceType", "source_type").ifBlank { fallbackType.name }.trim().lowercase()
        val type = when (rawType) {
            "m3u", "m3u8" -> SourceType.M3U
            "stalker" -> SourceType.STALKER
            else -> SourceType.XTREAM
        }

        val server = pick(fields, "server", "url").trim()
        val user = pick(fields, "user", "username").trim()
        val pass = pick(fields, "pass", "password").trim()
        val portalUrl = pick(fields, "portalUrl", "portal_url").trim()
        val mac = pick(fields, "mac", "macAddress", "mac_address").trim()

        when (type) {
            SourceType.STALKER -> if (portalUrl.isBlank() || mac.isBlank()) return null
            SourceType.M3U -> if (server.isBlank()) return null
            SourceType.XTREAM, SourceType.LOCAL_BACKUP -> if (server.isBlank() || user.isBlank() || pass.isBlank()) return null
        }

        return CompanionPayload(
            type = type,
            name = pick(fields, "name").trim(),
            server = server,
            user = user,
            pass = pass,
            portalUrl = portalUrl,
            mac = mac,
            userAgent = pick(fields, "userAgent", "user_agent").trim(),
            epgUrl = pick(fields, "epgUrl", "epg_url").trim(),
            autoRefresh = pick(fields, "autoRefresh", "auto_refresh").ifBlank { "OFF" },
            syncLive = scopeChoice(fields, "syncLive", "sync_live", default = tv.own.owntv.core.sync.SyncScopeChoice.Now),
            syncMovies = scopeChoice(fields, "syncMovies", "sync_movies", default = tv.own.owntv.core.sync.SyncScopeChoice.Now),
            syncSeries = scopeChoice(fields, "syncSeries", "sync_series", default = tv.own.owntv.core.sync.SyncScopeChoice.Now),
            isDefault = bool(fields, "isDefault", "is_default", default = false),
        )
    }

    private fun parseJsonFields(bodyText: String): Map<String, String> {
        val json = org.json.JSONObject(bodyText)
        val out = LinkedHashMap<String, String>()
        json.keys().forEach { key ->
            when (val value = json.opt(key)) {
                null, org.json.JSONObject.NULL -> Unit
                else -> out[key] = value.toString()
            }
        }
        return out
    }

    private fun pick(fields: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> fields[key]?.takeIf { it.isNotBlank() } } ?: ""

    private fun bool(fields: Map<String, String>, primary: String, secondary: String, default: Boolean): Boolean {
        val raw = fields[primary] ?: fields[secondary] ?: return default
        return raw.equals("true", true) || raw == "1" || raw.equals("on", true) || raw.equals("yes", true)
    }

    private fun scopeChoice(
        fields: Map<String, String>,
        primary: String,
        secondary: String,
        default: tv.own.owntv.core.sync.SyncScopeChoice,
    ): tv.own.owntv.core.sync.SyncScopeChoice {
        val raw = fields[primary] ?: fields[secondary] ?: return default
        return tv.own.owntv.core.sync.SyncScopeChoice.parse(raw, default)
    }

    private fun sendHtml(socket: Socket, code: Int, body: String) =
        sendBytes(socket, code, "text/html; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    private fun sendText(socket: Socket, code: Int, body: String) =
        sendBytes(socket, code, "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    /** Streams [bytes] as a file download so the browser saves it rather than rendering it. */
    private fun sendDownload(socket: Socket, bytes: ByteArray, filename: String) {
        runCatching {
            socket.getOutputStream().use { out ->
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(StandardCharsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun sendBytes(socket: Socket, code: Int, contentType: String, bytes: ByteArray) {
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            413 -> "Payload Too Large"; else -> "OK"
        }
        runCatching {
            socket.getOutputStream().use { out ->
                val header = buildString {
                    append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                    append("Content-Type: ").append(contentType).append("\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(StandardCharsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (buffer.size() == 0) return null
                break
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) buffer.write(next)
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    companion object {
        private const val TAG = "CompanionServer"

        /** Backup JSON / base64 image uploads. Generous, but finite. */
        internal const val UPLOAD_BODY_LIMIT = 16 * 1024 * 1024

        /** PIN posts and source forms — a few hundred bytes in practice. */
        internal const val FORM_BODY_LIMIT = 64 * 1024

        /** Consecutive wrong PINs before the link closes itself (C2). */
        internal const val MAX_PIN_ATTEMPTS = 10

        /** Fixed pause before answering a wrong PIN (C2) — fixed so it carries no timing signal. */
        internal const val FAILED_PIN_DELAY_MS = 500L

        internal const val LOCKOUT_MESSAGE = "Too many incorrect PIN attempts — the link was closed for safety."

        /** Threads the companion server may take from the shared IO pool (C3). */
        private const val MAX_PARALLELISM = 6

        /** Connections served at once (C3); further ones are closed immediately rather than queued. */
        private const val MAX_IN_FLIGHT = 16

        private const val INITIAL_BODY_BUFFER = 64 * 1024
        private const val BODY_CHUNK = 16 * 1024

        /** Length-checked, constant-time PIN compare so timing cannot leak the PIN digit by digit. */
        fun pinEquals(submitted: String, expected: String): Boolean {
            if (submitted.length != expected.length) return false
            var diff = 0
            for (i in expected.indices) diff = diff or (submitted[i].code xor expected[i].code)
            return diff == 0
        }

        /** Parse an `application/x-www-form-urlencoded` string (query or body) into a key -> value map. */
        fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            query.split('&').forEach { pair ->
                if (pair.isBlank()) return@forEach
                val split = pair.split('=', limit = 2)
                val key = urlDecode(split[0])
                if (key.isNotBlank()) out[key] = urlDecode(split.getOrNull(1).orEmpty())
            }
            return out
        }

        private fun urlDecode(value: String): String =
            runCatching { URLDecoder.decode(value.replace('+', ' '), StandardCharsets.UTF_8.name()) }.getOrDefault(value)
    }
}
