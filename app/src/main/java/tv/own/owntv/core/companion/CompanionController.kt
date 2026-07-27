package tv.own.owntv.core.companion

import android.content.Context
import android.util.Log
import java.io.File
import java.net.BindException
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.R

/**
 * Owns the Remote companion HTTP listener for the app's lifetime. Registered as a Koin `single` so
 * [tv.own.owntv.features.setup.SetupViewModel] and [tv.own.owntv.features.settings.SettingsViewModel]
 * share one server instead of each carrying duplicate networking code.
 *
 * The phone only fills the Add Source form; it never starts the import. Each submission is exposed two
 * ways:
 *  - [payloads] — a live event stream, used by the Remote screen to know when to hand off; and
 *  - [lastPayload] — the retained latest value, so the Manual form (which subscribes *after* the
 *    hand-off navigation) still sees it. A replay-0 SharedFlow alone would drop it in that window.
 *
 * [start]/[stop] are safe to call from the UI thread; the server does its I/O on its own dispatcher.
 */
class CompanionController(context: Context) {

    private val appContext = context.applicationContext
    private val server = CompanionHttpServer()

    private val _state = MutableStateFlow<CompanionServerState>(CompanionServerState.Idle)
    val state: StateFlow<CompanionServerState> = _state.asStateFlow()

    private val _payloads = MutableSharedFlow<CompanionPayload>(extraBufferCapacity = 8)
    val payloads: SharedFlow<CompanionPayload> = _payloads.asSharedFlow()

    private val _lastPayload = MutableStateFlow<CompanionPayload?>(null)
    val lastPayload: StateFlow<CompanionPayload?> = _lastPayload.asStateFlow()

    /** Backup files uploaded from a phone in [startForBackupRestore] mode, saved to cache and emitted here. */
    private val _backups = MutableSharedFlow<File>(extraBufferCapacity = 4)
    val backups: SharedFlow<File> = _backups.asSharedFlow()

    /** Cache folder the remote-export flow writes the backup into before serving it for download. */
    val backupExportDir: File get() = File(appContext.cacheDir, "remote-backup-export").apply { mkdirs() }

    /** Image files uploaded from a phone in [startForImageUpload] mode, saved to cache and emitted here. */
    private val _images = MutableSharedFlow<File>(extraBufferCapacity = 4)
    val images: SharedFlow<File> = _images.asSharedFlow()

    /** A fresh 6-digit PIN per [start], so a leaked code is short-lived. */
    @Volatile private var currentPin: String = ""

    /** Bundled Lora TTF bytes, loaded once and served on the web form; null if the resource can't be read. */
    private val loraBytes: ByteArray? by lazy {
        // openRawResource serves any file-backed resource, not just res/raw; a font is exactly that,
        // and we want the TTF bytes verbatim rather than a loaded Typeface. runCatching covers the
        // resource being unavailable, which is the only thing the lint check is protecting against.
        @Suppress("ResourceType")
        runCatching { appContext.resources.openRawResource(R.font.lora_variable).use { it.readBytes() } }
            .onFailure { Log.w(TAG, "Could not load Lora for companion web form; falling back to serif", it) }
            .getOrNull()
    }

    /** Starts the add-source companion server (the original Remote add-source flow). */
    fun start(port: Int) = startInternal(port, CompanionMode.ADD_SOURCE)

    /** Starts the companion server in backup-restore mode: the phone uploads a backup JSON, emitted on [backups]. */
    fun startForBackupRestore(port: Int) = startInternal(port, CompanionMode.BACKUP_RESTORE)

    /** Starts the companion server in backup-download mode, serving [file] for the phone to download. */
    fun startForBackupDownload(port: Int, file: File) = startInternal(port, CompanionMode.BACKUP_DOWNLOAD, file)

    /** Starts the companion server in image-upload mode: the phone sends a background image, emitted on [images]. */
    fun startForImageUpload(port: Int) = startInternal(port, CompanionMode.IMAGE_UPLOAD)

    private fun startInternal(port: Int, mode: CompanionMode, downloadFile: File? = null) {
        if (port !in 1..65535) {
            _state.value = CompanionServerState.Failed("Enter a valid port between 1 and 65535.")
            return
        }
        _state.value = CompanionServerState.Starting
        try {
            currentPin = generatePin()
            _lastPayload.value = null
            val urls = server.start(
                port = port,
                pin = currentPin,
                fontBytes = loraBytes,
                mode = mode,
                onPayload = { payload ->
                    Log.d(TAG, "Received ${payload.type} payload '${payload.name.ifBlank { "(unnamed)" }}' — forwarding to UI")
                    _lastPayload.value = payload
                    _payloads.tryEmit(payload)
                },
                onBackup = ::onBackupUploaded,
                onImage = ::onImageUploaded,
                downloadFile = downloadFile,
                onLocked = {
                    // The server has already stopped itself; just reflect it on the TV so the user
                    // knows why the link went away and that restarting gives them a new PIN.
                    Log.w(TAG, "Companion link locked out after repeated wrong PINs")
                    currentPin = ""
                    _state.value = CompanionServerState.Locked
                },
            )
            _state.value = CompanionServerState.Listening(
                port = port,
                urls = urls,
                pin = currentPin,
                // The QR encodes the plain URL only — the PIN is typed on the phone's gate page.
                qr = CompanionLink.renderQr(CompanionLink.lanUrl(port)),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start companion listener", t)
            _state.value = CompanionServerState.Failed(friendlyError(t, port))
        }
    }

    /**
     * Persists an uploaded backup to a cache file and emits it for the restore UI to inspect.
     *
     * Two wire shapes, because a `.own` container is binary while the old `.json` was not:
     * a **data-URL** (`data:...;base64,…`) is decoded back to bytes — the same trick the background
     * image upload uses, so the socket layer stays text-only — and anything else is a legacy JSON
     * backup pasted through verbatim. The extension follows suit so the restore path's format sniff
     * and the temp file agree.
     */
    private fun onBackupUploaded(text: String) {
        runCatching {
            val base64 = text.substringAfter("base64,", missingDelimiterValue = "")
                .takeIf { text.startsWith("data:") && it.isNotBlank() }
            val bytes = base64?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            val file = File.createTempFile(
                "owntv-remote-restore",
                if (bytes != null) ".own" else ".json",
                appContext.cacheDir,
            )
            if (bytes != null) file.writeBytes(bytes) else file.writeText(text)
            Log.d(TAG, "Received remote backup (${file.length()} bytes) → ${file.name}")
            file
        }.onSuccess { _backups.tryEmit(it) }
            .onFailure { Log.w(TAG, "Failed to persist uploaded backup", it) }
    }

    /** Persists an uploaded background image to a cache file and emits it for the settings UI to ingest. */
    private fun onImageUploaded(bytes: ByteArray, extension: String) {
        runCatching {
            val file = File.createTempFile("owntv-remote-bg", ".$extension", appContext.cacheDir)
            file.writeBytes(bytes)
            Log.d(TAG, "Received remote background image (${bytes.size} bytes) → ${file.name}")
            file
        }.onSuccess { _images.tryEmit(it) }
            .onFailure { Log.w(TAG, "Failed to persist uploaded image", it) }
    }

    fun stop() {
        server.stop()
        currentPin = ""
        _state.value = CompanionServerState.Idle
    }

    /** Clears [lastPayload] after the Manual Add Source form consumed it for pre-fill. */
    fun consumePayload() {
        _lastPayload.value = null
    }

    /** Permanent teardown for the app-wide singleton. */
    fun dispose() {
        server.close()
        _state.value = CompanionServerState.Idle
    }

    private fun generatePin(): String = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')

    private fun friendlyError(t: Throwable, port: Int): String = when (t) {
        is BindException -> "Port $port is already in use. Try again to pick a fresh one."
        else -> t.message?.takeIf { it.isNotBlank() } ?: "Could not open the local server."
    }

    private companion object {
        const val TAG = "CompanionController"
    }
}
