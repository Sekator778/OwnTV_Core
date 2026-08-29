package tv.own.owntv.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the Remote companion LAN helpers (no socket / no device needed). */
class CompanionLinkTest {

    @Test
    fun `private ranges are recognised`() {
        assertTrue(CompanionLink.isPrivateLan("10.0.0.5"))
        assertTrue(CompanionLink.isPrivateLan("192.168.1.42"))
        assertTrue(CompanionLink.isPrivateLan("172.16.0.1"))
        assertTrue(CompanionLink.isPrivateLan("172.31.255.254"))
    }

    @Test
    fun `public and edge addresses are not private`() {
        assertFalse(CompanionLink.isPrivateLan("8.8.8.8"))
        assertFalse(CompanionLink.isPrivateLan("172.15.0.1")) // just below the 172.16 range
        assertFalse(CompanionLink.isPrivateLan("172.32.0.1")) // just above the 172.31 range
        assertFalse(CompanionLink.isPrivateLan("127.0.0.1"))
    }

    @Test
    fun `lanUrls always yields at least a loopback url with the port`() {
        val urls = CompanionLink.lanUrls(8089)
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { it.startsWith("http://") && it.endsWith(":8089/") })
    }

    @Test
    fun `lanUrl carries the requested port`() {
        assertEquals("http://", CompanionLink.lanUrl(9000).substringBefore("1").take(7))
        assertTrue(CompanionLink.lanUrl(9000).endsWith(":9000/"))
    }
}
