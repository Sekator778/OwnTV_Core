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
 * Destructive fallback is enabled while the schema is still evolving (pre-1.0); real migrations
 * arrive before release.
 */
val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), OwnTVDatabase::class.java, OwnTVDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                OwnTVDatabase.MIGRATION_1_2,
                OwnTVDatabase.MIGRATION_2_3,
                OwnTVDatabase.MIGRATION_3_4,
                OwnTVDatabase.MIGRATION_4_6,
                OwnTVDatabase.MIGRATION_6_7,
                OwnTVDatabase.MIGRATION_7_8,
                OwnTVDatabase.MIGRATION_8_9,
                OwnTVDatabase.MIGRATION_9_10,
                OwnTVDatabase.MIGRATION_10_11,
                OwnTVDatabase.MIGRATION_11_12,
                OwnTVDatabase.MIGRATION_12_13,
                OwnTVDatabase.MIGRATION_13_14,
                OwnTVDatabase.MIGRATION_14_15,
                OwnTVDatabase.MIGRATION_15_16,
                OwnTVDatabase.MIGRATION_16_17,
            )
            .fallbackToDestructiveMigration(dropAllTables = true) // safety net for unforeseen jumps
            .addCallback(object : RoomDatabase.Callback() {
                // Self-heal index/FTS drift on every open (no-op when healthy): an interrupted bulk
                // import can leave BulkInsertHelper's dropped indexes missing, which is invisible
                // now but fails Room's full-schema validation at the NEXT version bump (the
                // 4.0.x → 4.1.0 crash-loop). Healing here repairs drift long before that migration.
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    runCatching { OwnTVDatabase.healSchema(db) }
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
    single { get<OwnTVDatabase>().tvProviderProgramDao() }
    single { get<OwnTVDatabase>().downloadDao() }
    single { get<OwnTVDatabase>().epgDao() }
    single { get<OwnTVDatabase>().metadataDao() }
    single { get<OwnTVDatabase>().subtitleDao() }
}
