package tv.own.owntv.core.metadata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.metadataBudgetStore: DataStore<Preferences> by
    preferencesDataStore(name = "owntv_metadata_budget")

/**
 * A snapshot of the current install's metadata allowance, for the Settings status row.
 *
 * [remainingDay] is the number that matters to a user; [resetAtMs] is when the daily window rolls over.
 */
data class MetadataBudgetStatus(
    val usedMinute: Int,
    val limitMinute: Int,
    val usedHour: Int,
    val limitHour: Int,
    val usedDay: Int,
    val limitDay: Int,
    val resetAtMs: Long,
) {
    val remainingMinute: Int get() = (limitMinute - usedMinute).coerceAtLeast(0)
    val remainingHour: Int get() = (limitHour - usedHour).coerceAtLeast(0)
    val remainingDay: Int get() = (limitDay - usedDay).coerceAtLeast(0)

    /** True when ANY window is spent — that is what actually stops a lookup. */
    val exhausted: Boolean get() = remainingMinute <= 0 || remainingHour <= 0 || remainingDay <= 0
}

/**
 * Per-install allowance for the shared default metadata Worker.
 *
 * **Why this exists.** The Worker's quota is a single pool shared by every install, and nothing
 * previously stopped one of them consuming all of it — which is exactly what happened. This caps any
 * single install so a runaway loop, a broken build or a hostile script can cost the pool a bounded
 * amount instead of everything.
 *
 * Three windows, because they catch different failures:
 *  - **per minute** — a tight loop; deliberately generous (a Trending rebuild or arrowing down an
 *    episode list is a legitimate burst, and choking it would feel like a bug)
 *  - **per hour** — a stuck retry that a minute window would let through forever
 *  - **per day** — the ceiling that actually protects the shared pool
 *
 * Applies ONLY to the default-Worker tier. A user's own TMDB key or self-hosted Worker is their own
 * resource and is never metered here.
 *
 * Counters are fixed windows (not a rolling bucket) persisted in DataStore, so a restart can't reset
 * the allowance. Fixed windows can allow up to 2x the limit across a boundary; that is fine for an
 * abuse ceiling and much cheaper than persisting a full token bucket.
 */
class MetadataBudget(
    private val context: Context,
    private val limitMinute: Int = LIMIT_MINUTE,
    private val limitHour: Int = LIMIT_HOUR,
    private val limitDay: Int = LIMIT_DAY,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private val _refusedAt = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * Timestamp of the most recent refusal, 0 until one happens. The shell observes this to tell the
     * user once per launch that their allowance is used up, instead of metadata silently vanishing.
     */
    val refusedAt: kotlinx.coroutines.flow.StateFlow<Long> = _refusedAt

    /**
     * Consume one request if the allowance permits. Returns false when a window is exhausted — the
     * caller then behaves exactly as it does when the network is down (returns null, no negative
     * cache), so the UI falls back to provider data instead of showing an error.
     */
    suspend fun tryConsume(): Boolean = mutex.withLock {
        val t = now()
        var allowed = false
        context.metadataBudgetStore.edit { p ->
            val minute = roll(p, t, KEY_MINUTE_START, KEY_MINUTE_COUNT, MINUTE_MS)
            val hour = roll(p, t, KEY_HOUR_START, KEY_HOUR_COUNT, HOUR_MS)
            val day = roll(p, t, KEY_DAY_START, KEY_DAY_COUNT, DAY_MS)
            allowed = minute < limitMinute && hour < limitHour && day < limitDay
            if (allowed) {
                p[KEY_MINUTE_COUNT] = minute + 1
                p[KEY_HOUR_COUNT] = hour + 1
                p[KEY_DAY_COUNT] = day + 1
            }
        }
        if (!allowed) _refusedAt.value = t
        allowed
    }

    /** Current usage, for the Settings status row. Read-only — never advances a counter. */
    suspend fun status(): MetadataBudgetStatus {
        val t = now()
        val p = context.metadataBudgetStore.data.first()
        val dayStart = p[KEY_DAY_START] ?: t
        val expired = t - dayStart >= DAY_MS
        return MetadataBudgetStatus(
            usedMinute = if (t - (p[KEY_MINUTE_START] ?: t) >= MINUTE_MS) 0 else (p[KEY_MINUTE_COUNT] ?: 0),
            limitMinute = limitMinute,
            usedHour = if (t - (p[KEY_HOUR_START] ?: t) >= HOUR_MS) 0 else (p[KEY_HOUR_COUNT] ?: 0),
            limitHour = limitHour,
            usedDay = if (expired) 0 else (p[KEY_DAY_COUNT] ?: 0),
            limitDay = limitDay,
            resetAtMs = if (expired) t + DAY_MS else dayStart + DAY_MS,
        )
    }

    /**
     * Read the count for one window, resetting it first if the window has elapsed. Writes the window
     * start back so the reset is persisted even when the request is then refused.
     */
    private fun roll(
        p: androidx.datastore.preferences.core.MutablePreferences,
        t: Long,
        startKey: Preferences.Key<Long>,
        countKey: Preferences.Key<Int>,
        windowMs: Long,
    ): Int {
        val start = p[startKey]
        // A clock that jumped backwards (NTP, user change) would otherwise freeze the window open
        // forever, so treat any negative age as a fresh window too.
        if (start == null || t - start >= windowMs || t < start) {
            p[startKey] = t
            p[countKey] = 0
            return 0
        }
        return p[countKey] ?: 0
    }

    companion object {
        /** Generous: short bursts are legitimate (Trending rebuild, scrolling an episode list). */
        const val LIMIT_MINUTE = 40

        /** Catches a stuck retry loop that the per-minute window would let run indefinitely. */
        const val LIMIT_HOUR = 150

        /**
         * The ceiling that protects the shared pool. Derived from real usage, not from an install
         * count: a normal user resolves 40–80 titles a day and a first-day user exploring a large
         * catalog reaches 300–500, so 400 sits at roughly the top of genuine use while stopping the
         * kind of runaway that consumed the entire daily pool.
         */
        const val LIMIT_DAY = 400

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS

        private val KEY_MINUTE_START = longPreferencesKey("minute_start")
        private val KEY_MINUTE_COUNT = intPreferencesKey("minute_count")
        private val KEY_HOUR_START = longPreferencesKey("hour_start")
        private val KEY_HOUR_COUNT = intPreferencesKey("hour_count")
        private val KEY_DAY_START = longPreferencesKey("day_start")
        private val KEY_DAY_COUNT = intPreferencesKey("day_count")
    }
}
