package tv.own.owntv.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType

@RunWith(AndroidJUnit4::class)
class OwnTVDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrateVersion2ToCurrent_preservesUserData_andUnifiesTvProviderAndCatchup() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion2Database()

        val db = openWithAllMigrations()

        try {
            val sqlite = db.openHelper.readableDatabase
            assertTableExists(sqlite, "tv_provider_programs")
            assertIndexExists(sqlite, "index_tv_provider_programs_profileId_surface_mediaType_groupId")
            assertColumnExists(sqlite, "channels", "catchup")
            assertColumnExists(sqlite, "channels", "catchupDays")
            assertColumnExists(sqlite, "channels", "catchupSource")

            assertCount(sqlite, "profiles", 1)
            assertCount(sqlite, "sources", 1)
            assertCount(sqlite, "profile_source", 1)
            assertCount(sqlite, "categories", 3)
            assertCount(sqlite, "channels", 1)
            assertCount(sqlite, "movies", 1)
            assertCount(sqlite, "series", 1)
            assertCount(sqlite, "seasons", 1)
            assertCount(sqlite, "episodes", 1)
            assertCount(sqlite, "watch_history", 3)
            assertCount(sqlite, "playback_progress", 2)
            assertCount(sqlite, "tv_provider_programs", 0)
        } finally {
            db.close()
        }
    }

    /** Dev devices on unreleased main builds (DB v7 with content_order, no contentHash). */
    @Test
    fun migrateVersion7ToCurrent_addsContentHashesAndEpgNaturalKey() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion7Database()

        val db = openWithAllMigrations()

        try {
            val sqlite = db.openHelper.readableDatabase
            assertColumnExists(sqlite, "channels", "contentHash")
            assertColumnExists(sqlite, "movies", "contentHash")
            assertColumnExists(sqlite, "series", "contentHash")
            assertColumnExists(sqlite, "epg_programmes", "contentHash")
            assertIndexExists(sqlite, "index_epg_programmes_natural_key")
            assertTableExists(sqlite, "content_order")
            assertIndexExists(sqlite, "index_content_order_profileId_mediaType_contextKey_itemId")
            // D1: v8→v9 now truncates the rebuildable programme cache (instant first launch)
            // instead of the old row-wise de-dup; the guide re-downloads on the next EPG sync.
            // epg_channels is untouched.
            assertCount(sqlite, "epg_programmes", 0)
            assertCount(sqlite, "epg_channels", 2)
        } finally {
            db.close()
        }
    }

    /** The public upgrade path: a real v3.2.0 database (DB v3) all the way to the current version. */
    @Test
    fun migrateVersion3ToCurrent_publicUpgradePath_preservesUserData() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion3Database()

        val db = openWithAllMigrations()

        try {
            val sqlite = db.openHelper.readableDatabase
            // Structure added along the way.
            assertTableExists(sqlite, "content_order")
            assertIndexExists(sqlite, "index_content_order_profileId_mediaType_contextKey_itemId")
            assertColumnExists(sqlite, "channels", "contentHash")
            assertColumnExists(sqlite, "movies", "contentHash")
            assertColumnExists(sqlite, "series", "contentHash")
            assertColumnExists(sqlite, "epg_programmes", "contentHash")
            assertIndexExists(sqlite, "index_channels_sourceId_sortOrder_name")
            assertIndexExists(sqlite, "index_movies_sourceId_remoteId")
            assertIndexExists(sqlite, "index_series_sourceId_remoteId")
            assertIndexExists(sqlite, "index_epg_programmes_natural_key")
            assertIndexExists(sqlite, "index_epg_programmes_sourceId_epgChannelId")
            assertTableExists(sqlite, "metadata_cache")
            assertColumnExists(sqlite, "metadata_cache", "logoPath")
            assertColumnExists(sqlite, "sources", "mac")
            assertIndexExists(sqlite, "index_movies_sourceId_rating_name")
            // User data survives.
            assertCount(sqlite, "profiles", 1)
            assertCount(sqlite, "sources", 1)
            assertCount(sqlite, "profile_source", 1)
            assertCount(sqlite, "categories", 3)
            assertCount(sqlite, "channels", 1)
            assertCount(sqlite, "movies", 1)
            assertCount(sqlite, "series", 1)
            assertCount(sqlite, "favorites", 3)
            assertCount(sqlite, "playback_progress", 2)
        } finally {
            db.close()
        }
    }

    /**
     * Regression for the 4.0.x → 4.1.0 upgrade crash: an interrupted bulk import leaves
     * BulkInsertHelper's dropped non-unique indexes missing. That drift is invisible while the DB
     * version doesn't change, but the next migration triggers Room's full-schema validation, which
     * used to throw "Migration didn't properly handle" and crash-loop the app at launch. The final
     * migration now runs OwnTVDatabase.healSchema, so opening a drifted v12 database must succeed
     * and end with every expected index back in place.
     */
    @Test
    fun migrateDriftedVersion12ToCurrent_healsDroppedIndexes() {
        context.deleteDatabase(DB_NAME)
        val db12 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db12, "tv.own.owntv.core.database.OwnTVDatabase/12.json")
            db12.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
            // Simulate the interrupted-import drift.
            db12.execSQL("DROP INDEX IF EXISTS `index_movies_sourceId_rating_name`")
            db12.execSQL("DROP INDEX IF EXISTS `index_series_categoryId_rating_name`")
            db12.execSQL("DROP INDEX IF EXISTS `index_channels_sourceId`")
            db12.execSQL("DROP INDEX IF EXISTS `index_epg_programmes_stopMs`")
            db12.version = 12
        } finally {
            db12.close()
        }

        val db = openWithAllMigrations()
        try {
            // Would throw IllegalStateException here without the heal (validation failure).
            val sqlite = db.openHelper.readableDatabase
            assertIndexExists(sqlite, "index_movies_sourceId_rating_name")
            assertIndexExists(sqlite, "index_series_categoryId_rating_name")
            assertIndexExists(sqlite, "index_channels_sourceId")
            assertIndexExists(sqlite, "index_epg_programmes_stopMs")
            assertColumnExists(sqlite, "metadata_cache", "logoPath")
            assertColumnExists(sqlite, "sources", "mac")
            assertColumnExists(sqlite, "sources", "syncLive")
            assertColumnExists(sqlite, "sources", "syncMovies")
            assertColumnExists(sqlite, "sources", "syncSeries")
            assertColumnExists(sqlite, "series", "episodesSyncedAt")
            assertCount(sqlite, "profiles", 1)
        } finally {
            db.close()
        }
    }

    /**
     * D2 — the tests above each pick one interesting starting version, which leaves the hops in
     * between covered only by accident. A user can arrive from *any* shipped version, so every
     * exported schema must migrate all the way to the current one and pass Room's full-schema
     * validation. This is the test that fails when a new migration is added without its predecessor
     * being reachable, or when a hand-written migration drifts from the entity definitions.
     *
     * Schema-only on purpose: seeding each version would mean hand-maintaining a column list per
     * version, and data preservation is already asserted from v2/v3/v7 above.
     */
    @Test
    fun everyExportedSchemaVersionMigratesToCurrent() {
        MIGRATABLE_START_VERSIONS.forEach { version ->
            context.deleteDatabase(DB_NAME)
            val old = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
            try {
                executeSchemaQueries(old, "tv.own.owntv.core.database.OwnTVDatabase/$version.json")
                old.version = version
            } finally {
                old.close()
            }

            val db = openWithAllMigrations()
            try {
                // Room validates the whole schema while opening; a broken hop throws here.
                val sqlite = db.openHelper.readableDatabase
                assertEquals("v$version did not reach the current version", CURRENT_VERSION, sqlite.version)
                OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.values.flatten().forEach {
                    assertIndexExists(sqlite, indexNameOf(it))
                }
            } finally {
                db.close()
            }
        }
    }

    /**
     * D2 — MIGRATION_4_6 skips version 5 rather than shipping 4→5 and 5→6. That is only safe
     * because v5 never reached a public build: it existed on dev machines for one change to
     * `favorites` that was reverted before release, so 4.json and 6.json describe the identical
     * schema (same identityHash). If a future change ever makes them differ, this no-op hop would
     * silently leave a v4 database malformed — so pin the property the shortcut depends on.
     */
    @Test
    fun migration4to6IsANoOpBecauseVersion5WasNeverPublic() {
        assertEquals(4, OwnTVDatabase.MIGRATION_4_6.startVersion)
        assertEquals(6, OwnTVDatabase.MIGRATION_4_6.endVersion)
        assertEquals(
            "4.json and 6.json describe different schemas — MIGRATION_4_6 can no longer be a no-op",
            identityHashOf(4),
            identityHashOf(6),
        )
    }

    /**
     * D2 — [OwnTVDatabase.healSchema] is the last line of defence against the interrupted-import
     * drift that crash-looped 4.0.x → 4.1.0. The test above proves it rescues one drifted upgrade;
     * this one proves it is complete: strip *every* object it claims to guarantee (all non-unique
     * indexes on the bulk-synced tables and all four external-content FTS tables) from a current
     * database, heal, and require the full set back — each FTS table actually queryable, not just
     * present in sqlite_master.
     */
    @Test
    fun healSchemaRestoresEveryGuaranteedIndexAndFtsTable() {
        context.deleteDatabase(DB_NAME)
        val expectedIndexes = OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.values.flatten().map(::indexNameOf)
        val expectedFts = OwnTVDatabase.EXPECTED_FTS_TABLES.keys

        val current = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(current, "tv.own.owntv.core.database.OwnTVDatabase/$CURRENT_VERSION.json")
            current.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
            expectedIndexes.forEach { current.execSQL("DROP INDEX IF EXISTS `$it`") }
            expectedFts.forEach { current.execSQL("DROP TABLE IF EXISTS `$it`") }
            current.version = CURRENT_VERSION
        } finally {
            current.close()
        }

        val db = openWithAllMigrations()
        try {
            val sqlite = db.openHelper.writableDatabase
            // Opening at the current version runs no migration, so nothing has healed yet.
            expectedIndexes.forEach { assertMissing(sqlite, "index", it) }
            expectedFts.forEach { assertMissing(sqlite, "table", it) }

            OwnTVDatabase.healSchema(sqlite)

            expectedIndexes.forEach { assertIndexExists(sqlite, it) }
            expectedFts.forEach { fts ->
                assertTableExists(sqlite, fts)
                // A CREATE VIRTUAL TABLE that registered but is unusable (missing shadow tables,
                // content table mismatch) only shows up when something reads from it.
                countRows(sqlite, "SELECT COUNT(*) FROM `$fts`")
            }
            // Idempotent: the app runs this on every drifted open.
            OwnTVDatabase.healSchema(sqlite)
            expectedIndexes.forEach { assertIndexExists(sqlite, it) }
            assertCount(sqlite, "profiles", 1)
        } finally {
            db.close()
        }
    }

    /** `CREATE INDEX IF NOT EXISTS \`name\` ON …` -> `name`. */
    private fun indexNameOf(createSql: String) =
        createSql.substringAfter("IF NOT EXISTS `").substringBefore('`')

    private fun identityHashOf(version: Int): String {
        val asset = "tv.own.owntv.core.database.OwnTVDatabase/$version.json"
        val json = JSONObject(testContext.assets.open(asset).bufferedReader().use { it.readText() })
        return json.getJSONObject("database").getString("identityHash")
    }

    private fun assertMissing(db: SupportSQLiteDatabase, type: String, name: String) {
        assertEquals(
            "$type $name should not exist yet",
            0L,
            countRows(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?", arrayOf<Any?>(type, name)),
        )
    }

    private fun openWithAllMigrations() = Room.databaseBuilder(context, OwnTVDatabase::class.java, DB_NAME)
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
            OwnTVDatabase.MIGRATION_17_18,
        )
        .allowMainThreadQueries()
        .build()

    private fun bootstrapVersion2Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db, "tv.own.owntv.core.database.OwnTVDatabase/2.json")
            seedVersion2Data(db)
            db.version = 2
        } finally {
            db.close()
        }
    }

    private fun bootstrapVersion7Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db, "tv.own.owntv.core.database.OwnTVDatabase/7.json")
            seedVersion7Data(db)
            db.version = 7
        } finally {
            db.close()
        }
    }

    private fun bootstrapVersion3Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db, "tv.own.owntv.core.database.OwnTVDatabase/3.json")
            seedVersion3Data(db)
            db.version = 3
        } finally {
            db.close()
        }
    }

    /** Representative v3.2.0 user data (column lists match 3.json exactly). */
    private fun seedVersion3Data(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
        db.execSQL("INSERT INTO sources (id, name, type, url, username, password, userAgent, epgUrl, createdAt, lastSyncAt) VALUES (10, 'Playlist', '${SourceType.XTREAM.name}', 'https://example.test', 'user', 'pass', NULL, NULL, 2, 3)")
        db.execSQL("INSERT INTO profile_source (profileId, sourceId) VALUES (1, 10)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (20, 10, '${MediaType.LIVE.name}', 'Live', 'cat-live', 0)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (21, 10, '${MediaType.MOVIE.name}', 'Movies', 'cat-movies', 1)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (22, 10, '${MediaType.SERIES.name}', 'Series', 'cat-series', 2)")
        db.execSQL(
            "INSERT INTO channels (id, sourceId, categoryId, name, logoUrl, streamUrl, epgChannelId, number, remoteId, sortOrder, catchup, catchupDays, catchupSource) " +
                "VALUES (30, 10, 20, 'News', 'https://example.test/logo.png', 'https://example.test/live.m3u8', 'news-epg', 1, 'ch-30', 0, 1, 7, 'default')",
        )
        db.execSQL(
            "INSERT INTO movies (id, sourceId, categoryId, name, posterUrl, backdropUrl, year, rating, durationSecs, plot, streamUrl, containerExt, remoteId, addedAt, sortOrder) " +
                "VALUES (40, 10, 21, 'Movie One', 'https://example.test/movie.jpg', NULL, 2026, 8.1, 7200, 'Plot', 'https://example.test/movie.mp4', 'mp4', 'movie-40', 4, 0)",
        )
        db.execSQL(
            "INSERT INTO series (id, sourceId, categoryId, name, posterUrl, backdropUrl, year, rating, plot, remoteId, sortOrder) " +
                "VALUES (50, 10, 22, 'Show One', 'https://example.test/show.jpg', NULL, 2026, 8.4, 'Plot', 'series-50', 0)",
        )
        db.execSQL("INSERT INTO seasons (id, seriesId, seasonNumber, name, remoteId) VALUES (60, 50, 1, 'Season 1', 'season-1')")
        db.execSQL(
            "INSERT INTO episodes (id, seriesId, seasonId, seasonNumber, episodeNumber, name, plot, streamUrl, durationSecs, containerExt, remoteId) " +
                "VALUES (70, 50, 60, 1, 1, 'Episode 1', NULL, 'https://example.test/episode1.mp4', 3600, 'mp4', 'episode-70')",
        )
        db.execSQL("INSERT INTO favorites (id, profileId, mediaType, itemId, addedAt) VALUES (80, 1, '${MediaType.LIVE.name}', 30, 100)")
        db.execSQL("INSERT INTO favorites (id, profileId, mediaType, itemId, addedAt) VALUES (81, 1, '${MediaType.MOVIE.name}', 40, 101)")
        db.execSQL("INSERT INTO favorites (id, profileId, mediaType, itemId, addedAt) VALUES (82, 1, '${MediaType.SERIES.name}', 50, 102)")
        db.execSQL("INSERT INTO watch_history (id, profileId, mediaType, itemId, watchedAt) VALUES (85, 1, '${MediaType.MOVIE.name}', 40, 110)")
        db.execSQL("INSERT INTO playback_progress (id, profileId, mediaType, itemId, positionMs, durationMs, updatedAt) VALUES (90, 1, '${MediaType.MOVIE.name}', 40, 120000, 7200000, 200)")
        db.execSQL("INSERT INTO playback_progress (id, profileId, mediaType, itemId, positionMs, durationMs, updatedAt) VALUES (91, 1, '${MediaType.EPISODE.name}', 70, 150000, 3600000, 201)")
        db.execSQL("INSERT INTO epg_channels (id, sourceId, epgChannelId, displayName) VALUES (1, -1, 'news', 'News')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (10, -1, 'news', 1000, 2000, 'News One', 'A')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (11, -1, 'news', 1000, 2000, 'News Duplicate', 'B')")
    }

    private fun executeSchemaQueries(db: SQLiteDatabase, assetPath: String) {
        val json = JSONObject(testContext.assets.open(assetPath).bufferedReader().use { it.readText() })
        val database = json.getJSONObject("database")
        val entities = database.getJSONArray("entities")
        for (entityIndex in 0 until entities.length()) {
            val entity = entities.getJSONObject(entityIndex)
            val tableName = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.optJSONArray("indices") ?: continue
            for (index in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
        val setupQueries = database.getJSONArray("setupQueries")
        for (index in 0 until setupQueries.length()) {
            db.execSQL(setupQueries.getString(index))
        }
    }

    private fun seedVersion2Data(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
        db.execSQL("INSERT INTO sources (id, name, type, url, username, password, userAgent, epgUrl, createdAt, lastSyncAt) VALUES (10, 'Playlist', '${SourceType.XTREAM.name}', 'https://example.test', 'user', 'pass', NULL, NULL, 2, 3)")
        db.execSQL("INSERT INTO profile_source (profileId, sourceId) VALUES (1, 10)")

        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (20, 10, '${MediaType.LIVE.name}', 'Live', 'cat-live', 0)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (21, 10, '${MediaType.MOVIE.name}', 'Movies', 'cat-movies', 1)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (22, 10, '${MediaType.SERIES.name}', 'Series', 'cat-series', 2)")

        db.execSQL("INSERT INTO channels (id, sourceId, categoryId, name, logoUrl, streamUrl, epgChannelId, number, remoteId, sortOrder) VALUES (30, 10, 20, 'News', 'https://example.test/logo.png', 'https://example.test/live.m3u8', 'news-epg', 1, 'ch-30', 0)")
        db.execSQL("INSERT INTO movies (id, sourceId, categoryId, name, posterUrl, backdropUrl, year, rating, durationSecs, plot, streamUrl, containerExt, remoteId, addedAt, sortOrder) VALUES (40, 10, 21, 'Movie One', 'https://example.test/movie.jpg', NULL, 2026, 8.1, 7200, 'Plot', 'https://example.test/movie.mp4', 'mp4', 'movie-40', 4, 0)")
        db.execSQL("INSERT INTO series (id, sourceId, categoryId, name, posterUrl, backdropUrl, year, rating, plot, remoteId, sortOrder) VALUES (50, 10, 22, 'Series One', 'https://example.test/show.jpg', NULL, 2026, 8.4, 'Plot', 'series-50', 0)")
        db.execSQL("INSERT INTO seasons (id, seriesId, seasonNumber, name, remoteId) VALUES (60, 50, 1, 'Season 1', 'season-1')")
        db.execSQL("INSERT INTO episodes (id, seriesId, seasonId, seasonNumber, episodeNumber, name, plot, streamUrl, durationSecs, containerExt, remoteId) VALUES (70, 50, 60, 1, 1, 'Episode 1', 'Plot', 'https://example.test/episode1.mp4', 3600, 'mp4', 'episode-70')")

        db.execSQL("INSERT INTO watch_history (id, profileId, mediaType, itemId, watchedAt) VALUES (80, 1, '${MediaType.LIVE.name}', 30, 100)")
        db.execSQL("INSERT INTO watch_history (id, profileId, mediaType, itemId, watchedAt) VALUES (81, 1, '${MediaType.MOVIE.name}', 40, 101)")
        db.execSQL("INSERT INTO watch_history (id, profileId, mediaType, itemId, watchedAt) VALUES (82, 1, '${MediaType.SERIES.name}', 50, 102)")

        db.execSQL("INSERT INTO playback_progress (id, profileId, mediaType, itemId, positionMs, durationMs, updatedAt) VALUES (90, 1, '${MediaType.MOVIE.name}', 40, 120000, 7200000, 200)")
        db.execSQL("INSERT INTO playback_progress (id, profileId, mediaType, itemId, positionMs, durationMs, updatedAt) VALUES (91, 1, '${MediaType.EPISODE.name}', 70, 150000, 3600000, 201)")
    }

    private fun seedVersion7Data(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO epg_channels (id, sourceId, epgChannelId, displayName) VALUES (1, -1, 'news', 'News')")
        db.execSQL("INSERT INTO epg_channels (id, sourceId, epgChannelId, displayName) VALUES (2, -1, 'sports', 'Sports')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (10, -1, 'news', 1000, 2000, 'News One', 'A')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (11, -1, 'news', 1000, 2000, 'News Duplicate', 'B')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (12, -1, 'sports', 3000, 4000, 'Sports One', 'C')")
    }

    private fun assertTableExists(db: SupportSQLiteDatabase, table: String) {
        assertEquals(1L, countRows(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(table)))
    }

    private fun assertIndexExists(db: SupportSQLiteDatabase, index: String) {
        assertEquals(1L, countRows(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf<Any?>(index)))
    }

    private fun assertColumnExists(db: SupportSQLiteDatabase, table: String, column: String) {
        assertEquals(
            1L,
            countRows(
                db,
                "SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = ?",
                arrayOf<Any?>(column),
            ),
        )
    }

    private fun assertCount(db: SupportSQLiteDatabase, table: String, expected: Long) {
        assertEquals(expected, countRows(db, "SELECT COUNT(*) FROM `$table`"))
    }

    private fun countRows(db: SupportSQLiteDatabase, sql: String, args: Array<Any?> = emptyArray()): Long {
        db.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            if (!cursor.moveToFirst()) return 0L
            return cursor.getLong(0)
        }
    }

    companion object {
        private const val DB_NAME = "owntv-migration-test.db"

        /** Must match `@Database(version = …)` on [OwnTVDatabase]. */
        private const val CURRENT_VERSION = 18

        /**
         * Every version with an exported schema that a real database can be sitting at.
         * Deliberate omissions:
         *  - 1 and 8 were never exported, so no database can be reconstructed at them.
         *  - 5 exists on disk but was never public and has no migration out of it (MIGRATION_4_6
         *    jumps over it); see [migration4to6IsANoOpBecauseVersion5WasNeverPublic].
         */
        private val MIGRATABLE_START_VERSIONS = listOf(2, 3, 4, 6, 7, 9) + (10 until CURRENT_VERSION)
    }
}
