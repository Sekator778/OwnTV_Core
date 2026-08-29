package tv.own.owntv.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MetadataMaturityPolicyTest {
    @Test
    fun `only kids profiles exclude adult TMDB results`() {
        assertEquals(true, profileAllowsAdultMetadata(isKids = false))
        assertEquals(false, profileAllowsAdultMetadata(isKids = true))
        assertEquals(true, profileAllowsAdultMetadata(isKids = null))
    }

    @Test
    fun `normal profile keeps existing match key`() {
        assertEquals(
            "movie:7:abc",
            MetadataRepository.maturityMatchKey("movie:7:abc", includeAdult = true),
        )
    }

    @Test
    fun `kids match cache cannot reuse normal profile match`() {
        val normal = MetadataRepository.maturityMatchKey("movie:7:abc", includeAdult = true)
        val kids = MetadataRepository.maturityMatchKey("movie:7:abc", includeAdult = false)
        assertNotEquals(normal, kids)
        assertEquals("movie:7:abc:kids", kids)
    }
}
