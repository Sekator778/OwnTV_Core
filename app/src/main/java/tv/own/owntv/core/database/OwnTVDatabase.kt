package tv.own.owntv.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.TvProviderProgramDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ChannelFtsEntity
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.EpisodeFtsEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.MovieFtsEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SeasonEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SeriesFtsEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.database.entity.TvProviderProgramEntity

@Database(
    entities = [
        // Profiles & sources
        ProfileEntity::class,
        SourceEntity::class,
        ProfileSourceCrossRef::class,
        // Content
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        // User data (profile-scoped)
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        PlaybackProgressEntity::class,
        ContentOrderEntity::class,
        DownloadEntity::class,
        // Android TV home-screen bookkeeping
        TvProviderProgramEntity::class,
        // EPG
        EpgChannelEntity::class,
        EpgProgrammeEntity::class,
        // FTS (search)
        ChannelFtsEntity::class,
        MovieFtsEntity::class,
        SeriesFtsEntity::class,
        EpisodeFtsEntity::class,
    ],
    version = 9, // v7: content_order (Move). v8: contentHash + browse/unique indexes. v9: EPG contentHash + natural key

    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OwnTVDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun sourceDao(): SourceDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun progressDao(): ProgressDao
    abstract fun contentOrderDao(): ContentOrderDao
    abstract fun tvProviderProgramDao(): TvProviderProgramDao
    abstract fun downloadDao(): DownloadDao
    abstract fun epgDao(): EpgDao

    companion object {
        const val NAME = "owntv.db"

        /**
         * v1 → v2: drop the foreign key on the EPG tables (standalone EPG sources use ids that
         * aren't in `sources`). EPG data is transient and re-synced, so the tables are recreated
         * empty — everything else (profiles, sources, content, favorites, history) is preserved.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `epg_programmes`")
                db.execSQL("DROP TABLE IF EXISTS `epg_channels`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `epg_channels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `epgChannelId` TEXT NOT NULL, `displayName` TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_channels_sourceId` ON `epg_channels` (`sourceId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_channels_sourceId_epgChannelId` ON `epg_channels` (`sourceId`, `epgChannelId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `epg_programmes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `epgChannelId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `stopMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_epgChannelId_startMs` ON `epg_programmes` (`epgChannelId`, `startMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId` ON `epg_programmes` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_stopMs` ON `epg_programmes` (`stopMs`)")
            }
        }

        /**
         * v2 → v3:
         * - add catch-up/archive columns to `channels` (pure additive ALTERs with defaults)
         * - add Android TV provider bookkeeping for Watch Next / Continue Watching rows
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchup` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupDays` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupSource` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tv_provider_programs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profileId` INTEGER NOT NULL, " +
                        "`surface` TEXT NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, " +
                        "`targetItemId` INTEGER NOT NULL, " +
                        "`providerProgramId` INTEGER, " +
                        "`lastPositionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`lastEngagementAt` INTEGER NOT NULL, " +
                        "`lastPublishedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tv_provider_programs_profileId` ON `tv_provider_programs` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tv_provider_programs_profileId_surface_mediaType_groupId` ON `tv_provider_programs` (`profileId`, `surface`, `mediaType`, `groupId`)")
            }
        }

        /**
         * v3 → v4: v3 existed in the wild in two incompatible variants (catch-up vs Android TV home
         * bookkeeping). v4 unifies them by ensuring BOTH the catch-up columns and the provider table
         * exist, regardless of which v3 a user has.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Channels catch-up columns (skip if already present).
                if (!hasColumn(db, "channels", "catchup")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchup` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "channels", "catchupDays")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupDays` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "channels", "catchupSource")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupSource` TEXT")
                }

                // Android TV provider bookkeeping table (safe to run repeatedly).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tv_provider_programs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profileId` INTEGER NOT NULL, " +
                        "`surface` TEXT NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, " +
                        "`targetItemId` INTEGER NOT NULL, " +
                        "`providerProgramId` INTEGER, " +
                        "`lastPositionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`lastEngagementAt` INTEGER NOT NULL, " +
                        "`lastPublishedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tv_provider_programs_profileId` ON `tv_provider_programs` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tv_provider_programs_profileId_surface_mediaType_groupId` ON `tv_provider_programs` (`profileId`, `surface`, `mediaType`, `groupId`)")

                // EPG-perf Guide read-index (v4.0.0). Declared on EpgProgrammeEntity, so v4 expects it; older
                // DBs (and the runtime ensureEpgIndexes) create it too — make sure the migrated DB has it.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId_epgChannelId` ON `epg_programmes` (`sourceId`, `epgChannelId`)")
            }
        }

        /**
         * v4 → v6: main's v5 briefly added `favorites.sortOrder` and v6 removed it again, so the v4
         * and v6 schemas are identical — a no-op hop keeps the public 3 → latest chain unbroken.
         * (Dev builds that sat exactly on the transient v5 fall back to the destructive safety net,
         * same as on main; v5 never shipped publicly.)
         */
        val MIGRATION_4_6 = object : androidx.room.migration.Migration(4, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // v4 and v6 schemas are identical.
            }
        }

        /**
         * v6 → v7: manual reorder (Move) — per-profile `content_order` table. This is main's v7 and
         * must keep that meaning: dev devices on unreleased main builds already sit on it.
         */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                createContentOrderTable(db)
            }
        }

        /**
         * v7 → v8: incremental sync (PR #40) — `contentHash` on channels/movies/series plus the
         * browse composite indexes and the unique `(sourceId, remoteId)` movie/series indexes.
         * Everything is guarded so both v3.2.0-lineage and main-dev DBs (which already have the
         * indexes) migrate cleanly.
         */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "channels", "contentHash")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "movies", "contentHash")) {
                    db.execSQL("ALTER TABLE `movies` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "series", "contentHash")) {
                    db.execSQL("ALTER TABLE `series` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_name` ON `channels` (`sourceId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_name` ON `channels` (`categoryId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_sortOrder_name` ON `channels` (`sourceId`, `sortOrder`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_sortOrder_name` ON `channels` (`categoryId`, `sortOrder`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_name` ON `movies` (`sourceId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_name` ON `movies` (`categoryId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_sortOrder_name` ON `movies` (`sourceId`, `sortOrder`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_sortOrder_name` ON `movies` (`categoryId`, `sortOrder`, `name`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_movies_sourceId_remoteId` ON `movies` (`sourceId`, `remoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_name` ON `series` (`sourceId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_name` ON `series` (`categoryId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_sortOrder_name` ON `series` (`sourceId`, `sortOrder`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_sortOrder_name` ON `series` (`categoryId`, `sortOrder`, `name`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_series_sourceId_remoteId` ON `series` (`sourceId`, `remoteId`)")
                // Early v4 dev builds shipped without the EPG guide read-index (it was added while the
                // version stayed 4) — heal them here since the 4→6 hop is a no-op.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId_epgChannelId` ON `epg_programmes` (`sourceId`, `epgChannelId`)")
            }
        }

        /**
         * v8 → v9: incremental EPG sync (PR #40) — `contentHash` on programmes plus the natural-key
         * unique index. Pre-existing duplicate rows are removed before the unique index is created.
         */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "epg_programmes", "contentHash")) {
                    db.execSQL("ALTER TABLE `epg_programmes` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL(
                    "DELETE FROM `epg_programmes` WHERE `id` NOT IN (" +
                        "SELECT MIN(`id`) FROM `epg_programmes` GROUP BY `sourceId`, `epgChannelId`, `startMs`" +
                        ")",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_programmes_natural_key` " +
                        "ON `epg_programmes` (`sourceId`, `epgChannelId`, `startMs`)",
                )
            }
        }

        private fun createContentOrderTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `content_order` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`profileId` INTEGER NOT NULL, " +
                    "`mediaType` TEXT NOT NULL, " +
                    "`contextKey` TEXT NOT NULL, " +
                    "`itemId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                    ")",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_order_profileId` ON `content_order` (`profileId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_order_profileId_mediaType_contextKey` ON `content_order` (`profileId`, `mediaType`, `contextKey`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_content_order_profileId_mediaType_contextKey_itemId` ON `content_order` (`profileId`, `mediaType`, `contextKey`, `itemId`)")
        }

        private fun hasColumn(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
                return false
            }
        }
    }
}
