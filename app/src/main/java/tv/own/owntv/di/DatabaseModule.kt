package tv.own.owntv.di

import androidx.room.Room
import androidx.room.RoomDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import tv.own.owntv.core.database.OwnTVDatabase

/**
 * Provides the Room database (WAL journal mode for fast concurrent reads during large imports) and
 * each DAO. Foreign-key enforcement is on by default in Room.
 *
 * There is deliberately NO destructive-migration fallback: [OwnTVDatabase.ALL_MIGRATIONS] covers every
 * shipped version, and a wipe-on-mismatch "safety net" would silently delete a user's profiles,
 * sources, favorites, history and resume positions on the first schema surprise. A missing or
 * failing migration must surface as an error we can fix, not as an empty app.
 */
val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), OwnTVDatabase::class.java, OwnTVDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*OwnTVDatabase.ALL_MIGRATIONS)
            .addCallback(object : RoomDatabase.Callback() {
                // Self-heal index/FTS drift on every open (no-op when healthy): an interrupted bulk
                // import can leave BulkInsertHelper's dropped indexes missing, which is invisible
                // now but fails Room's full-schema validation at the NEXT version bump (the
                // 4.0.x → 4.1.0 crash-loop). Healing here repairs drift long before that migration.
                //
                // ST4: gated behind a single sqlite_master count so a healthy open pays one index
                // lookup instead of ~30 DDL statements on the thread issuing the first query. The
                // heal itself stays in onOpen — moving it off would let a query beat it to a missing
                // index. The OwnTVPerf timeline reports both the cost and whether it healed.
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Connection tuning, applied before anything queries. Both settings are per-open,
                    // so they belong here rather than in a migration.
                    //
                    // synchronous=NORMAL is the documented companion to WAL: FULL fsyncs the WAL on
                    // every single commit, which on a TV's eMMC is the dominant cost of a sync writing
                    // tens of thousands of rows. NORMAL still fsyncs at checkpoints, so the durability
                    // it trades away is only "the last few committed transactions survive a sudden
                    // power cut" — not database integrity, which WAL still guarantees. A cut power
                    // cable can cost the tail of a catalogue import, and that re-syncs.
                    //
                    // The page cache is per-connection and defaults to ~2MB. Home alone opens a dozen
                    // profile-scoped queries over the same handful of index pages, and every screen
                    // after it re-reads them; a negative value is SQLite's "this many KiB" form.
                    runCatching {
                        db.query("PRAGMA synchronous=NORMAL").close()
                        db.query("PRAGMA cache_size=-8000").close()
                    }
                    val healed = runCatching { OwnTVDatabase.healSchemaIfDrifted(db) }.getOrDefault(false)
                    tv.own.owntv.Perf.stamp(if (healed) "db-heal(repaired)" else "db-heal(clean)")
                }
            })
            .build()
    }

    single { get<OwnTVDatabase>().profileDao() }
    single { get<OwnTVDatabase>().sourceDao() }
    single { get<OwnTVDatabase>().categoryDao() }
    single { get<OwnTVDatabase>().channelDao() }
    single { get<OwnTVDatabase>().movieDao() }
    single { get<OwnTVDatabase>().seriesDao() }
    single { get<OwnTVDatabase>().favoriteDao() }
    single { get<OwnTVDatabase>().historyDao() }
    single { get<OwnTVDatabase>().progressDao() }
    single { get<OwnTVDatabase>().contentOrderDao() }
    single { get<OwnTVDatabase>().playbackPrefsDao() }
    single { get<OwnTVDatabase>().customCategoryDao() }
    single { get<OwnTVDatabase>().seriesSortOrderDao() }
    single { get<OwnTVDatabase>().tvProviderProgramDao() }
    single { get<OwnTVDatabase>().downloadDao() }
    single { get<OwnTVDatabase>().epgDao() }
    single { get<OwnTVDatabase>().metadataDao() }
    single { get<OwnTVDatabase>().trendingDao() }
    single { get<OwnTVDatabase>().subtitleDao() }
}
