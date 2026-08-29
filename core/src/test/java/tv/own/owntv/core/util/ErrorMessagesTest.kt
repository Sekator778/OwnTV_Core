package tv.own.owntv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMessagesTest {
    @Test
    fun `known host resolution failure is classified for localized presentation`() {
        assertEquals(
            FriendlySyncFailure.Unreachable,
            classifySyncFailure("Unable to resolve host example.test", online = true),
        )
    }

    @Test
    fun `unmapped external failure preserves its raw text`() {
        val raw = "Provider-specific failure code X17"

        assertEquals(
            FriendlySyncFailure.Unknown(raw),
            classifySyncFailure(raw, online = true),
        )
    }
}
