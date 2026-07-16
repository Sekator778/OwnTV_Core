package tv.own.owntv.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Filesystem access for downloads & backup. Android TV usually lacks the SAF document-picker, so we
 * use plain [File] access: an app-specific dir works with no permission, and "All files access"
 * (MANAGE_EXTERNAL_STORAGE) unlocks user-chosen folders elsewhere on storage.
 */
object StorageAccess {

    /**
     * Whether shared storage can be browsed: All-files access on Android 11+, or the classic
     * READ_EXTERNAL_STORAGE grant on Android 10 and below. A media-only grant on 11–12L does NOT
     * count — the picker offers exactly one grant path (full access), never a media tier.
     */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One uniform grant route on every device: OwnTV's own App-info settings screen, where the
     * user picks storage access themselves (Permissions → Files and media / Storage →
     * "Allow management of all files"). Deliberately NOT the All-files intent — OEM builds hijack
     * it (TCL Android 12 routes it to "Permission Shield", which has no storage entry) — and NOT
     * a runtime permission dialog, which on 11–12L could only grant a useless media-only tier.
     * READ_EXTERNAL_STORAGE stays declared (maxSdk 32) so "Files and media" is listed there.
     */
    fun openStoragePermissionSettings(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** App-specific external dir — always writable, no permission. Visible under Android/data/<pkg>/files. */
    fun defaultRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "OwnTV").apply { mkdirs() }

    /** The effective base folder: the configured path if usable, else the default. */
    fun resolveRoot(context: Context, configured: String?): File {
        val dir = configured?.takeIf { it.isNotBlank() }?.let { File(it) }
        return if (dir != null && (dir.exists() || dir.mkdirs())) dir else defaultRoot(context)
    }

    /** Top-level browsable storage roots (label → dir) for the folder picker. */
    fun storageRoots(context: Context): List<Pair<String, File>> {
        val roots = LinkedHashMap<String, File>()
        val internal = Environment.getExternalStorageDirectory()
        if (internal != null && internal.exists()) roots["Internal storage"] = internal
        // Removable volumes: derive each volume root from its app-specific dir (…/Android/data/pkg/files).
        context.getExternalFilesDirs(null).forEach { f ->
            val vol = f?.parentFile?.parentFile?.parentFile?.parentFile
            if (vol != null && vol.exists() && vol.absolutePath != internal?.absolutePath) {
                roots[vol.name.ifBlank { "Removable storage" }] = vol
            }
        }
        roots["App storage (no permission needed)"] = defaultRoot(context)
        return roots.map { it.key to it.value }
    }

    /** Strips characters that are illegal in file/folder names. */
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "untitled" }.take(120)

    /** Best-effort file extension from a stream URL (defaults to mp4). */
    fun extOf(url: String): String {
        val ext = url.substringAfterLast('/', "").substringBefore('?').substringAfterLast('.', "")
        return ext.takeIf { it.isNotBlank() && it.length <= 4 } ?: "mp4"
    }
}
