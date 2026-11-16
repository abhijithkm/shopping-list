package space.abhijithkm.shoppinglist.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattingTest {
    @Test
    fun `whole numbers lose the decimal point`() {
        assertEquals("2", formatQuantity(2.0))
        assertEquals("12", formatQuantity(12.0))
        assertEquals("1000000", formatQuantity(1_000_000.0))
    }

    @Test
    fun `decimals keep at most three places`() {
        assertEquals("1.5", formatQuantity(1.5))
        assertEquals("0.125", formatQuantity(0.125))
        assertEquals("0.333", formatQuantity(0.33333))
    }

    @Test
    fun `quantity and unit are combined`() {
        assertEquals("2 kg", formatQuantityAndUnit(2.0, "kg"))
        assertEquals("12 pieces", formatQuantityAndUnit(12.0, " pieces "))
        assertEquals("3", formatQuantityAndUnit(3.0, null))
        assertEquals("3", formatQuantityAndUnit(3.0, "  "))
        assertEquals("kg", formatQuantityAndUnit(null, "kg"))
    }

    @Test
    fun `nothing to show gives null`() {
        assertNull(formatQuantityAndUnit(null, null))
        assertNull(formatQuantityAndUnit(null, ""))
    }
}
