package space.abhijithkm.shoppinglist.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidGeneratorTest {
    private val generator = UuidGenerator()

    @Test
    fun `produces random version 4 UUIDs`() {
        val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        repeat(50) { assertTrue(pattern.matches(generator.newId())) }
    }

    @Test
    fun `ids are unique`() {
        val ids = List(1_000) { generator.newId() }.toSet()
        assertEquals(1_000, ids.size)
    }

    @Test
    fun `ids fit the AppHub URL id rule`() {
        // AppHub accepts ids matching [A-Za-z0-9_-]{1,64} in URLs.
        val allowed = Regex("^[A-Za-z0-9_-]{1,64}$")
        assertTrue(allowed.matches(generator.newId()))
    }
}
