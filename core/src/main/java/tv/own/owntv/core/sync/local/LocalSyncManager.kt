package tv.own.owntv.core.sync.local

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.companion.CompanionController
import tv.own.owntv.core.companion.CompanionLink

/** Which way the data goes. Always the user's explicit choice — never inferred. */
enum class SyncDirection {
    /** This device's data goes to the other one. Nothing here changes. */
    SEND,

    /** The other device's data comes here. Nothing there changes. */
    RECEIVE,

    /** Both, receive first: the far side's changes land here, then this device's go back. */
    MERGE,
}

/** How far along a sync is, for the screen driving it. */
sealed interface SyncProgress {
    data object Idle : SyncProgress
    data object Connecting : SyncProgress
    data object Preparing : SyncProgress
    data object Transferring : SyncProgress
    data object Applying : SyncProgress
    data class Done(val received: BackupManager.ImportSummary?, val sent: Boolean) : SyncProgress
    data class Failed(val reason: SyncFailure) : SyncProgress
}

sealed interface SyncFailure {
    /** Nothing answered at that address — wrong network, or the other screen is closed. */
    data object Unreachable : SyncFailure

    /** The PIN was wrong, or the pairing was removed on the other device. */
    data object NotAuthorized : SyncFailure

    /** It answered, but what came back was not an OwnTV backup. */
    data object BadPayload : SyncFailure
    data object Unknown : SyncFailure
}

/**
 * Local sync: two OwnTV devices on the same Wi-Fi exchanging their data directly, with no account,
 * no cloud and no server of ours.
 *
 * It is deliberately thin, because nearly all of it already existed. The payload **is** a backup
 * container, so [BackupManager] writes and reads it unchanged; the applying **is** a restore, which
 * has merged rather than overwritten since 2026-07-18; the listener **is** the companion server the
 * Remote flow uses, with one extra mode. What is new is the client
 * ([LocalSyncClient]), the pairing ([PairedDeviceStore]) and the deletions
 * ([tv.own.owntv.core.database.entity.UserDataTombstoneEntity]) — without which a merge quietly
 * reinstates everything the user has ever removed.
 *
 * Both apps use this class and both can host, so a sync can be started from whichever device the
 * user happens to be holding.
 */
class LocalSyncManager(
    context: Context,
    private val companion: CompanionController,
    private val backups: BackupManager,
    private val paired: PairedDeviceStore,
    private val client: LocalSyncClient,
    private val discovery: LocalSyncDiscovery,
) {
    private val appContext = context.applicationContext

    /**
     * The secrets this listener will accept in place of the PIN, snapshotted when hosting starts and
     * extended as devices pair. Held in memory because the server reads it from inside a request.
     */
    @Volatile private var acceptedSecrets: Set<String> = emptySet()

    /** Outlives a single request: the pairing write is launched here, not awaited on a server thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    val pairedDevices: Flow<List<PairedDevice>> = paired.devices

    /** The listener's own state — the PIN, the QR and the addresses to show while hosting. */
    val hostState: Flow<tv.own.owntv.core.companion.CompanionServerState> = companion.state

    /** What the other device will call this one. The name the user gave the phone, if they gave one. */
    val deviceName: String by lazy {
        val configured = runCatching { Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
        configured?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    // --- hosting ---------------------------------------------------------------------------------

    /**
     * Starts listening and announces this device on the network. The PIN and address to show come
     * from [CompanionController.state], as they do for every other companion flow.
     *
     * The export is prepared **now**, once, rather than when the other device asks for it: a request
     * has a timeout and building a container out of a large library does not always fit inside one.
     * That also means what the other device receives is this device's state at the moment the screen
     * opened, which is the moment the user is looking at.
     *
     * Running only while that screen is open is the entire security posture, so the caller must
     * [stopHosting] when it leaves.
     */
    suspend fun startHosting(
        port: Int = CompanionLink.DEFAULT_PORT,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
        password: String? = null,
        profileIds: Set<Long>? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val folder = File(cacheDir(), "outgoing").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val exported = backups.export(folder, sections, password, profileIds)
            .getOrElse { return@withContext Result.failure(it) }
        // Read once, here, rather than per request: the server asks for these while answering, and a
        // device unpaired mid-session simply stops working on the next session, which is soon enough.
        acceptedSecrets = paired.secrets()
        startServing(port, File(exported))
        Result.success(Unit)
    }

    private fun startServing(port: Int, file: File) {
        companion.startForLocalSync(
            port = port,
            file = file,
            info = {
                JSONObject()
                    .put("name", deviceName)
                    .put("app", CoreBuildInfo.versionName)
                    .put("payload", PAYLOAD_VERSION)
                    .toString()
            },
            onPair = { remoteName, remoteAddress -> pairFromHost(remoteName, remoteAddress) },
            secrets = { acceptedSecrets },
        )
        discovery.advertise(deviceName, port)
    }

    fun stopHosting() {
        discovery.stopAdvertising()
        companion.stop()
        acceptedSecrets = emptySet()
    }

    fun discover(): Flow<DiscoveredDevice> = discovery.discover()

    /**
     * Containers the other device has pushed to this one while hosting. Nothing is applied on
     * arrival — the screen previews it and the user confirms, because an unattended device must not
     * change its own data because something on the network asked it to.
     */
    val incoming: Flow<File> = companion.backups

    /**
     * Called on the HOST when the other device presents the right PIN: mint its secret, remember it,
     * and hand it back.
     *
     * The secret is added to [acceptedSecrets] before the store is written, and the write itself is
     * launched rather than waited for. This runs *inside* an HTTP request on the server's own small
     * thread pool: blocking one of those threads on a DataStore write is how a busy listener stops
     * answering, and the far side would be handed a secret it cannot use until the disk catches up.
     */
    private fun pairFromHost(remoteName: String, remoteAddress: String): String? = runCatching {
        val secret = PairedDeviceStore.newSecret()
        acceptedSecrets = acceptedSecrets + secret
        scope.launch {
            paired.put(
                PairedDevice(
                    id = UUID.randomUUID().toString(),
                    name = remoteName.ifBlank { UNKNOWN_DEVICE_NAME },
                    // Where it connected FROM, so this device can start a sync towards it later
                    // instead of only ever being the one connected to. Its port is the default one:
                    // both apps host on it, and nothing in a pairing request says otherwise.
                    address = remoteAddress,
                    port = CompanionLink.DEFAULT_PORT,
                    secret = secret,
                    pairedAt = System.currentTimeMillis(),
                ),
            )
        }
        secret
    }.onFailure { Log.w(TAG, "Pairing failed on the host side", it) }.getOrNull()

    // --- connecting ------------------------------------------------------------------------------

    /**
     * First contact: the user typed the PIN shown on the other device. Trades it for a lasting
     * secret and remembers the device, so nothing is typed again.
     */
    suspend fun pair(address: String, port: Int, pin: String): Result<PairedDevice> {
        val secret = client.pair(address, port, pin, deviceName).getOrElse { return Result.failure(it) }
        val remote = client.hello(address, port, secret).getOrNull()
        val device = PairedDevice(
            id = UUID.randomUUID().toString(),
            name = remote?.name?.takeIf { it.isNotBlank() } ?: address,
            address = address,
            port = port,
            secret = secret,
            pairedAt = System.currentTimeMillis(),
        )
        paired.put(device)
        return Result.success(device)
    }

    suspend fun unpair(id: String) = paired.remove(id)

    // --- transferring ----------------------------------------------------------------------------

    /**
     * Downloads the other device's data into a file WITHOUT applying any of it, so the user can be
     * shown what would change before anything does. [apply] finishes the job.
     */
    suspend fun fetch(
        device: PairedDevice,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
        password: String? = null,
    ): Result<Pair<File, BackupManager.Preview>> =
        withContext(Dispatchers.IO) {
            _progress.value = SyncProgress.Connecting
            val target = File(cacheDir(), "incoming-${System.currentTimeMillis()}.own")
            _progress.value = SyncProgress.Transferring
            client.fetch(device.address, device.port, device.secret, target)
                .mapCatching { file ->
                    // The user's chosen sections, not all of them: a summary that counts favorites
                    // the user just unticked promises a change that the apply will not make.
                    val preview = backups.previewImport(file, sections, password).getOrThrow()
                    paired.touch(device.id, device.address, device.port)
                    _progress.value = SyncProgress.Idle
                    file to preview
                }
                .onFailure { _progress.value = SyncProgress.Failed(failureFor(it)) }
        }

    /** What a container that arrived here would change. For a push, which nobody asked for yet. */
    suspend fun preview(
        file: File,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
        password: String? = null,
    ): Result<BackupManager.Preview> = backups.previewImport(file, sections, password)

    /** Applies a fetched file — the ordinary restore path, merging, with the chosen sections only. */
    suspend fun apply(
        file: File,
        sections: Set<BackupManager.Section>,
        password: String? = null,
    ): Result<BackupManager.ImportSummary> {
        _progress.value = SyncProgress.Applying
        return backups.import(file, sections, password)
            .onSuccess { _progress.value = SyncProgress.Done(received = it, sent = false) }
            .onFailure { _progress.value = SyncProgress.Failed(failureFor(it)) }
    }

    /**
     * Exports the chosen sections and hands the file to the other device, which merges it there.
     *
     * The far side is not asked to choose: the sender picked what to send, and a merge cannot destroy
     * anything on arrival. What it can do is add — which is why the receiving screen shows what
     * landed afterwards.
     */
    suspend fun send(
        device: PairedDevice,
        sections: Set<BackupManager.Section>,
        password: String? = null,
        profileIds: Set<Long>? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        _progress.value = SyncProgress.Preparing
        // Its own folder: the fetched file the user is still looking at lives in the cache too, and
        // clearing the whole cache here would delete it out from under the confirmation sheet.
        val folder = File(cacheDir(), "outgoing").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        backups.export(folder, sections, password, profileIds)
            .mapCatching { path ->
                _progress.value = SyncProgress.Transferring
                client.send(device.address, device.port, device.secret, File(path)).getOrThrow()
                paired.touch(device.id, device.address, device.port)
                _progress.value = SyncProgress.Done(received = null, sent = true)
            }
            .onFailure { _progress.value = SyncProgress.Failed(failureFor(it)) }
    }

    fun clearProgress() {
        _progress.value = SyncProgress.Idle
    }

    /** Cache for sync payloads only, wiped before each export so one transfer cannot pick up another. */
    private fun cacheDir(): File = File(appContext.cacheDir, "local-sync").apply { mkdirs() }

    private fun failureFor(t: Throwable): SyncFailure = when {
        t is LocalSyncHttpException && t.code == 401 -> SyncFailure.NotAuthorized
        t is LocalSyncHttpException -> SyncFailure.BadPayload
        t is java.io.IOException -> SyncFailure.Unreachable
        else -> SyncFailure.Unknown
    }

    private companion object {
        const val TAG = "LocalSyncManager"

        /** The backup schema this build writes; the far side reports its own in `/sync/hello`. */
        const val PAYLOAD_VERSION = 21
        const val UNKNOWN_DEVICE_NAME = "OwnTV"
    }
}
