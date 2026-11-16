package space.abhijithkm.shoppinglist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCategoryTest {
    @Test
    fun `there are exactly the eleven specified defaults, others last`() {
        assertEquals(11, DefaultCategory.entries.size)
        assertEquals(DefaultCategory.OTHERS, DefaultCategory.entries.last())
    }

    @Test
    fun `keys are unique and stable identifiers`() {
        val keys = DefaultCategory.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { Regex("^[a-z_]+$").matches(it) })
    }

    @Test
    fun `fromKey resolves known keys and tolerates null or unknown`() {
        assertEquals(DefaultCategory.DAIRY, DefaultCategory.fromKey("dairy"))
        assertNull(DefaultCategory.fromKey(null))
        assertNull(DefaultCategory.fromKey("not-a-category"))
    }
}
