package tv.own.owntv.player

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Live network throughput (bits/sec): bytes since [bitsPerSecond] was last read, divided by time since
 * then. Assumes a single regular poller (the debug overlay) — a second reader would steal bytes meant
 * for the first. Starts disabled — call [setEnabled] to activate.
 *
 * [peekBitsPerSecond] exposes the most recent reading without consuming the byte window, so a passive
 * reader (the top-bar chip) can show the value the overlay's poll last computed.
 */
class ThroughputTracker : TransferListener {
    private var pendingBytes = 0L
    private var lastReadMs = 0L
    @Volatile private var enabled = false
    @Volatile private var everTransferred = false
    /** Display cache of the last computed reading — survives [reset] so the chip doesn't blank between
     *  enable/disable toggles or while a fresh measurement window opens. */
    @Volatile private var lastBitsPerSecond = 0L

    /** True once any byte has been transferred while enabled — distinguishes "never measured" from
     *  "measured, currently 0". */
    val hasMeasured: Boolean
        get() = everTransferred

    val bitsPerSecond: Long
        get() = readAndReset()

    /** Most recent measured bitrate without disturbing the active measurement window — 0 until the first
     *  real reading. Callers that need a fresh value (the overlay) use [bitsPerSecond]; callers that just
     *  want to display the current value (the chip) use this. */
    val peekBitsPerSecond: Long
        get() = lastBitsPerSecond

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (!enabled) return
        everTransferred = true
        synchronized(this) { pendingBytes += bytesTransferred }
    }

    @Synchronized
    private fun readAndReset(): Long {
        val now = SystemClock.elapsedRealtime()
        if (lastReadMs == 0L) {
            lastReadMs = now
            return 0L
        }
        val elapsedMs = now - lastReadMs
        val bps = if (elapsedMs > 0) pendingBytes * 8_000 / elapsedMs else 0L
        pendingBytes = 0L
        lastReadMs = now
        if (bps > 0) lastBitsPerSecond = bps
        return bps
    }

    /** Enabling starts fresh rather than counting bytes from whenever tracking was last on. */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (enabled) reset()
    }

    @Synchronized
    fun reset() {
        pendingBytes = 0L
        lastReadMs = 0L
        everTransferred = false
        // Intentionally NOT clearing lastBitsPerSecond: keep the last good reading visible in the chip
        // until a fresh measurement window produces a new one.
    }
}
