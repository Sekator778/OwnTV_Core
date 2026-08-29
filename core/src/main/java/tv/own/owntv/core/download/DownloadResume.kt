package tv.own.owntv.core.download

import java.io.File

/**
 * The two byte-count decisions a transfer has to get right, pulled out of [DownloadEngine] so they
 * are unit-testable without a network or a database.
 *
 * The rule both of them encode: **the file on disk is the truth.** The in-memory progress counter
 * lags a write, and a killed process loses it entirely, so neither the resume offset nor the paused
 * byte count may ever be taken from it (audit item DL2).
 */
internal object DownloadResume {

    /** Where an HTTP `Range` request must start: exactly the bytes already on disk. */
    fun resumeOffset(file: File): Long = if (file.exists()) file.length().coerceAtLeast(0L) else 0L

    /**
     * Total size to report while transferring. When the server honoured our Range (206) the body is
     * only the remainder, so the already-written bytes have to be added back.
     */
    fun expectedTotal(append: Boolean, existing: Long, bodyLength: Long): Long =
        (if (append) existing else 0L) + bodyLength.coerceAtLeast(0L)

    /**
     * Byte count to persist when a transfer stops. Prefers the file's real length; falls back to the
     * recorded counter only when the file is gone (removable volume unmounted, user deleted it).
     */
    fun bytesOnDisk(file: File?, recorded: Long): Long =
        if (file != null && file.exists()) file.length() else recorded.coerceAtLeast(0L)
}
