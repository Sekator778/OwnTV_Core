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
    fun migrateVersion2To4_preservesUserData_andUnifiesTvProviderAndCatchup() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion2Database()

        val db = Room.databaseBuilder(context, OwnTVDatabase::class.java, DB_NAME)
            .addMigrations(OwnTVDatabase.MIGRATION_1_2, OwnTVDatabase.MIGRATION_2_3, OwnTVDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

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
                "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?",
                arrayOf<Any?>(table, column),
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
    }
}
