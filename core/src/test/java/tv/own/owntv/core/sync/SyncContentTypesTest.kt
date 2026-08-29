package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

class SyncContentTypesTest {

    @Test
    fun `effectiveFor intersects enabled and constrains M3U to live`() {
        val source = SourceEntity(
            name = "m3u", type = SourceType.M3U, url = "http://x",
            syncLive = true, syncMovies = true, syncSeries = true,
        )
        val effective = SyncContentTypes().effectiveFor(source)
        assertEquals(SyncContentTypes(live = true, movies = false, series = false), effective)
        assertTrue(effective.isCompleteFor(SyncContentTypes.enabledFor(source)))
        assertFalse(effective.isCompleteFor(SyncContentTypes.enabledOf(source)))
    }

    @Test
    fun `effectiveFor drops Off sections`() {
        val source = SourceEntity(
            name = "x", type = SourceType.XTREAM, url = "http://x",
            syncLive = true, syncMovies = false, syncSeries = true,
        )
        val effective = SyncContentTypes().effectiveFor(source)
        assertEquals(SyncContentTypes(live = true, movies = false, series = true), effective)
        assertTrue(effective.isCompleteFor(SyncContentTypes.enabledFor(source)))
    }

    @Test
    fun `remainderAfter is enabled-relative`() {
        val enabled = SyncContentTypes(live = true, movies = true, series = false)
        val priority = SyncContentTypes(live = true, movies = false, series = false)
        assertEquals(
            SyncContentTypes(live = false, movies = true, series = false),
            enabled.remainderAfter(priority),
        )
    }

    @Test
    fun `choices derive enabled and priority scopes`() {
        val enabled = SyncContentTypes.fromChoices(SyncScopeChoice.Now, SyncScopeChoice.Later, SyncScopeChoice.Off)
        val priority = SyncContentTypes.priorityFromChoices(SyncScopeChoice.Now, SyncScopeChoice.Later, SyncScopeChoice.Off)
        assertEquals(SyncContentTypes(live = true, movies = true, series = false), enabled)
        assertEquals(SyncContentTypes(live = true, movies = false, series = false), priority)
    }
}
