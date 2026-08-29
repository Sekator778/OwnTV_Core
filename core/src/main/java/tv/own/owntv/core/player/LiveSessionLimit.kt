package tv.own.owntv.core.player

/**
 * Core's one-way channel to the playback engine for a fact the catalogue learned first: this
 * panel allows only one concurrent session.
 *
 * Xtream sync reads `max_connections` from the panel and [tv.own.owntv.core.repository.ActiveSources]
 * re-states it for every already-synced source at startup, both of which happen long before any
 * player exists. The engine keeps the flag (it is what decides whether a second tune is worth
 * attempting at all), but core must not reach into the player to set it — so the player registers
 * [report] once at startup and core simply announces the fact.
 *
 * Unset, every announcement is a no-op: an app with no playback engine still syncs correctly.
 */
object LiveSessionLimit {

    /** Set once at startup by whoever owns the playback engine. */
    var report: ((url: String) -> Unit)? = null

    /** Announce that the panel serving [url] permits a single concurrent session. */
    fun singleSession(url: String) {
        report?.invoke(url)
    }
}
