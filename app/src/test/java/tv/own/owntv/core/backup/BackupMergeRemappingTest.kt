package tv.own.owntv.core.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 — a merge-restore never trusts the ids in the backup file: profiles are matched by name and
 * sources by type+url+username, so every id the file carries has to be rewritten to the device's own
 * id before anything is written. Getting that wrong is silent and destructive — a restored profile's
 * favorites, folder customizations or TMDB name overrides end up attached to a DIFFERENT source or a
 * different person, with no error anywhere. None of it was covered.
 */
class BackupMergeRemappingTest {

    // --- match keys: which rows merge, and which duplicate ---

    @Test
    fun `profiles match on the trimmed, case-folded name`() {
        assertEquals(profileMatchKey("Kids"), profileMatchKey("  KIDS  "))
        assertEquals(profileMatchKey("Kids"), profileMatchKey("kids"))
        assertTrue(profileMatchKey("Kids") != profileMatchKey("Kids 2"))
    }

    @Test
    fun `sources match on type, url and username, but not on the password`() {
        val a = sourceMatchKey("XTREAM", "https://portal.test ", "user")
        val b = sourceMatchKey("XTREAM", "https://portal.test", "user")
        assertEquals("a rotated password must still merge onto the same source", a, b)
        // One portal, two accounts — must stay two sources.
        assertTrue(a != sourceMatchKey("XTREAM", "https://portal.test", "other"))
        // Same URL added twice as different source types is still two sources.
        assertTrue(a != sourceMatchKey("M3U", "https://portal.test", "user"))
        // A missing username is not the same as a blank one being ignored: both fold to "".
        assertEquals(
            sourceMatchKey("M3U", "https://list.test", null),
            sourceMatchKey("M3U", "https://list.test", ""),
        )
    }

    // --- remapKeys: profile-id-keyed and source-id-keyed settings maps ---

    @Test
    fun `remapKeys rewrites mapped ids and passes unmapped keys through untouched`() {
        val file = JSONObject().put("1", "a").put("2", "b").put("9", "c")
        val out = remapKeys(file, mapOf(1L to 40L, 2L to 41L))

        assertEquals("a", out.getString("40"))
        assertEquals("b", out.getString("41"))
        // 9 had no match on this device — kept as-is so a later profile-id filter can drop it,
        // rather than being silently rewritten onto someone else.
        assertEquals("c", out.getString("9"))
        assertEquals(3, out.length())
    }

    @Test
    fun `remapKeys leaves non-numeric keys alone and short-circuits on an empty map`() {
        val file = JSONObject().put("theme", "dark").put("3", "x")
        assertEquals("dark", remapKeys(file, mapOf(3L to 7L)).getString("theme"))
        // Empty map: the same instance back, no copying.
        assertSame(file, remapKeys(file, emptyMap()))
    }

    @Test
    fun `remapKeys collapsing two file ids onto one device id keeps a single entry`() {
        // Two profiles in the file whose names both match the same device profile.
        val out = remapKeys(JSONObject().put("1", "first").put("2", "second"), mapOf(1L to 5L, 2L to 5L))
        assertEquals(1, out.length())
        assertNotNull(out.opt("5"))
    }

    // --- remapCustomizationValue: "<sourceId>:<rest>" content keys inside a customization blob ---

    @Test
    fun `customization content keys are rewritten in both array and object fields`() {
        val raw = JSONObject()
            .put("hidden", JSONArray(listOf("10:movie:99", "11:live:7")))
            .put("renamed", JSONObject().put("10:series:3", "My Show"))
            .put("sortMode", "NAME")
            .toString()

        val out = JSONObject(remapCustomizationValue(raw, mapOf(10L to 200L)))

        val hidden = out.getJSONArray("hidden")
        assertEquals("200:movie:99", hidden.getString(0))
        assertEquals("11:live:7", hidden.getString(1)) // source 11 not on this device — untouched
        assertEquals("My Show", out.getJSONObject("renamed").getString("200:series:3"))
        assertNull(out.getJSONObject("renamed").opt("10:series:3"))
        assertEquals("NAME", out.getString("sortMode")) // scalar fields pass straight through
    }

    @Test
    fun `a customization key whose prefix is not a source id is left alone`() {
        val raw = JSONObject().put("hidden", JSONArray(listOf("all:movie:1"))).toString()
        val out = JSONObject(remapCustomizationValue(raw, mapOf(10L to 200L)))
        assertEquals("all:movie:1", out.getJSONArray("hidden").getString(0))
    }

    @Test
    fun `malformed customization json is returned verbatim rather than dropped`() {
        // Restore must degrade to "this setting stays as it was", never to an exception mid-import.
        assertEquals("not json at all", remapCustomizationValue("not json at all", mapOf(1L to 2L)))
        assertEquals("{\"a\":1}", remapCustomizationValue("{\"a\":1}", emptyMap()))
    }

    // --- remapTmdbOverrideKeys: "type:<sourceId>:<rest>" ---

    @Test
    fun `tmdb override keys rewrite only the middle source-id segment`() {
        val raw = JSONObject()
            .put("movie:10:42", "Blade Runner")
            .put("series:10:some:id:with:colons", "The Show")
            .put("movie:11:1", "Unmapped Source")
            .toString()

        val out = JSONObject(remapTmdbOverrideKeys(raw, mapOf(10L to 200L)))

        assertEquals("Blade Runner", out.getString("movie:200:42"))
        // split(limit = 3): everything after the source id is one opaque remainder, colons and all.
        assertEquals("The Show", out.getString("series:200:some:id:with:colons"))
        assertEquals("Unmapped Source", out.getString("movie:11:1"))
    }

    @Test
    fun `tmdb override keys without three segments are passed through`() {
        val raw = JSONObject().put("movie:10", "Two segments").toString()
        val out = JSONObject(remapTmdbOverrideKeys(raw, mapOf(10L to 200L)))
        assertEquals("Two segments", out.getString("movie:10"))
    }

    @Test
    fun `malformed tmdb override json is returned verbatim`() {
        assertEquals("{oops", remapTmdbOverrideKeys("{oops", mapOf(1L to 2L)))
    }

    // --- filterByProfile: the "only the ticked profiles" gate ---

    @Test
    fun `filterByProfile keeps only the selected profile ids`() {
        val map = JSONObject().put("1", "a").put("2", "b").put("3", "c")
        val out = filterByProfile(map, setOf("1", "3"))
        assertEquals(2, out.length())
        assertEquals("a", out.getString("1"))
        assertEquals("c", out.getString("3"))
        assertNull(out.opt("2"))
    }

    @Test
    fun `filterByProfile with no selection keeps nothing`() {
        assertEquals(0, filterByProfile(JSONObject().put("1", "a"), emptySet()).length())
    }

    /**
     * The order the import applies these in matters: keys are remapped to device ids FIRST, then
     * filtered against the device's profile ids. Filtering first would test file ids against device
     * ids and throw away everything whenever the two devices number their profiles differently.
     */
    @Test
    fun `remap then filter keeps the restored profile, filter then remap loses it`() {
        val file = JSONObject().put("1", "kids-config")
        val profileIdMap = mapOf(1L to 77L)
        val deviceProfileIds = setOf("77")

        val correct = filterByProfile(remapKeys(file, profileIdMap), deviceProfileIds)
        assertEquals("kids-config", correct.getString("77"))

        val wrongOrder = remapKeys(filterByProfile(file, deviceProfileIds), profileIdMap)
        assertEquals(0, wrongOrder.length())
    }
}
