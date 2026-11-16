package space.abhijithkm.shoppinglist.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SortOrderTest {
    @Test
    fun `appending goes one step after the last key`() {
        assertEquals(1024.0, SortOrder.after(null), 0.0)
        assertEquals(2048.0, SortOrder.after(1024.0), 0.0)
    }

    @Test
    fun `between picks the midpoint`() {
        assertEquals(15.0, SortOrder.between(10.0, 20.0)!!, 0.0)
    }

    @Test
    fun `at the ends it steps away from the single neighbour`() {
        assertEquals(1024.0, SortOrder.between(null, null)!!, 0.0)
        assertEquals(-1014.0, SortOrder.between(null, 10.0)!!, 0.0)
        assertEquals(1034.0, SortOrder.between(10.0, null)!!, 0.0)
    }

    @Test
    fun `an exhausted gap asks for renumbering`() {
        assertNull(SortOrder.between(1.0, 1.0))
        assertNull(SortOrder.between(1.0, 1.0000001))
        assertNotNull(SortOrder.between(1.0, 1.000003))
    }

    @Test
    fun `repeated halving eventually exhausts the gap and renumbering restores it`() {
        var above = 0.0
        val below = SortOrder.STEP
        var splits = 0
        while (true) {
            val mid = SortOrder.between(above, below) ?: break
            above = mid
            splits++
        }
        assertTrue("expected many splits before exhaustion, got $splits", splits > 25)

        val renumbered = SortOrder.renumbered(3)
        assertEquals(listOf(1024.0, 2048.0, 3072.0), renumbered)
        assertNotNull(SortOrder.between(renumbered[0], renumbered[1]))
    }
}
