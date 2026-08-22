package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TuneState] is the group of fields a new live tune must not inherit from the previous one, and the
 * reason it exists is that the hand-written 40-line reset it replaced could silently miss a field.
 *
 * These tests pin the two properties that make the replacement safe: a fresh instance really is the
 * cleared state, and **every** field participates in that state — a field added to the class but left
 * out of `equals` (declared in the body instead of the constructor) would be reset in practice but
 * invisible to any test comparing states, so it is caught here rather than on a TV.
 */
class TuneStateTest {

    @Test
    fun `resetting produces a state equal to a fresh one`() {
        val used = TuneState().apply {
            hasPlayed = true
            retryCount = 3
            gaveUp = true
            everRendered = true
            lastCodecError = "0x80001000"
            audioTrackList = listOf(TrackOption(label = "English", mpvId = 1, selected = false))
            frozenChecks = 7
            playStartedMs = 12_345L
        }
        assertNotEquals("a used tune must not compare equal to a fresh one", TuneState(), used)
        // What play() actually does — one assignment, not a field-by-field clear.
        val afterReset = TuneState()
        assertEquals(TuneState(), afterReset)
    }

    @Test
    fun `the cleared state is the documented default, not merely zero`() {
        val fresh = TuneState()
        // Two fields whose "cleared" value is deliberately not the zero value. A stream is assumed to
        // have video until its own track list says otherwise (assuming none would arm the no-picture
        // watchdog against every stream), and "no position seen yet" is -1 because 0 is a real position.
        assertTrue("a fresh tune assumes the stream has video", fresh.hasVideoTrack)
        assertEquals("no position seen yet", -1L, fresh.lastProgressPos)
    }

    @Test
    fun `every field takes part in the reset comparison`() {
        // A property declared in the class body instead of the primary constructor is excluded from a
        // data class's equals, so it would be reset by `TuneState()` yet invisible to the test above.
        // Java reflection, not kotlin-reflect (not on the unit-test classpath): a data class generates
        // one componentN() per *constructor* property, so a mismatch against the declared fields is
        // exactly a field that equals() would ignore.
        val fields = TuneState::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
        val components = TuneState::class.java.declaredMethods.count { it.name.startsWith("component") }
        assertEquals(
            "TuneState has ${fields.size} fields but only $components in equals() — a field declared " +
                "outside the primary constructor is not compared when checking that a tune was reset",
            fields.size,
            components,
        )
    }
}
