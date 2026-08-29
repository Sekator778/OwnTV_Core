package tv.own.owntv.core.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * The `.own` container: round-trips, format sniffing, and the legacy path that must never break.
 *
 * On-device rather than a plain JVM test because the container leans on `org.json` and
 * `android.util.Base64`, both of which are stubs under `src/test`.
 *
 * The case that matters most here is [legacy json file still opens]: users upgrading from any
 * pre-4.2 release still have bare `owntv-backup.json` files, and restore has to keep accepting them.
 */
@RunWith(AndroidJUnit4::class)
class BackupContainerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val json = """{"version":14,"profiles":[]}"""
    private val wallpaper = BackupContainer.Asset("background_1.jpg", byteArrayOf(1, 2, 3, 0, -7, 42))

    private fun write(bytes: ByteArray, name: String = "backup.own"): File =
        File(folder.root, name).apply { writeBytes(bytes) }

    @Test
    fun plain_container_round_trips_with_the_wallpaper() {
        val file = write(BackupContainer.pack(BackupContainer.Payload(json, wallpaper), passphrase = null))

        assertEquals(BackupContainer.Kind.CONTAINER, BackupContainer.probe(file))
        val payload = BackupContainer.open(file)
        assertEquals(json, payload.json)
        assertEquals("background_1.jpg", payload.wallpaper?.name)
        assertArrayEquals(wallpaper.bytes, payload.wallpaper?.bytes)
    }

    @Test
    fun container_without_a_wallpaper_round_trips() {
        val file = write(BackupContainer.pack(BackupContainer.Payload(json, null), passphrase = null))

        assertEquals(json, BackupContainer.open(file).json)
        assertNull(BackupContainer.open(file).wallpaper)
    }

    @Test
    fun sealed_container_round_trips_with_the_right_password() {
        val file = write(BackupContainer.pack(BackupContainer.Payload(json, wallpaper), passphrase = "hunter2"))

        assertEquals(BackupContainer.Kind.ENCRYPTED_CONTAINER, BackupContainer.probe(file))
        val payload = BackupContainer.open(file, "hunter2")
        assertEquals(json, payload.json)
        assertArrayEquals(wallpaper.bytes, payload.wallpaper?.bytes)
    }

    /** The whole point of sealing: none of the content is recoverable from the raw bytes. */
    @Test
    fun sealed_container_leaks_nothing_in_plaintext() {
        val secret = """{"version":14,"sources":[{"url":"http://panel.example/get.php","username":"alice"}]}"""
        val bytes = BackupContainer.pack(BackupContainer.Payload(secret, wallpaper), passphrase = "hunter2")

        val raw = String(bytes, Charsets.ISO_8859_1)
        assertTrue("container must not leak URLs", !raw.contains("panel.example"))
        assertTrue("container must not leak usernames", !raw.contains("alice"))
        assertTrue("container must not leak the entry list", !raw.contains("backup.json"))
    }

    @Test(expected = BackupManager.WrongPasswordException::class)
    fun sealed_container_rejects_a_wrong_password() {
        val file = write(BackupContainer.pack(BackupContainer.Payload(json, null), passphrase = "hunter2"))
        BackupContainer.open(file, "wrong")
    }

    @Test(expected = BackupManager.WrongPasswordException::class)
    fun sealed_container_rejects_a_missing_password() {
        val file = write(BackupContainer.pack(BackupContainer.Payload(json, null), passphrase = "hunter2"))
        BackupContainer.open(file, null)
    }

    @Test
    // No backticked/spaced name: minSdk 26 dexes below DEX 040, where spaces in method names are illegal.
    fun legacyJsonFileStillOpens() {
        val file = write(json.toByteArray(), name = "owntv-backup.json")

        assertEquals(BackupContainer.Kind.LEGACY_JSON, BackupContainer.probe(file))
        val payload = BackupContainer.open(file)
        assertEquals(json, payload.json)
        assertNull("a legacy backup carries no wallpaper", payload.wallpaper)
    }

    /** Sniffing is by content, so a renamed file still restores — the companion upload relies on it. */
    @Test
    fun format_is_detected_from_content_not_the_extension() {
        val container = write(BackupContainer.pack(BackupContainer.Payload(json, null), null), name = "backup.json")
        assertEquals(BackupContainer.Kind.CONTAINER, BackupContainer.probe(container))
        assertEquals(json, BackupContainer.open(container).json)

        val legacy = write(json.toByteArray(), name = "backup.own")
        assertEquals(BackupContainer.Kind.LEGACY_JSON, BackupContainer.probe(legacy))
        assertEquals(json, BackupContainer.open(legacy).json)
    }

    @Test
    fun garbage_is_not_a_backup() {
        val file = write(byteArrayOf())
        assertEquals(BackupContainer.Kind.UNKNOWN, BackupContainer.probe(file))
        assertTrue(runCatching { BackupContainer.open(file) }.isFailure)
    }
}
