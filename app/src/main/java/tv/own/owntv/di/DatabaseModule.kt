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
            )
            .fallbackToDestructiveMigration(dropAllTables = true) // safety net for unforeseen jumps
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
}
