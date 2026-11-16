package space.abhijithkm.shoppinglist.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.abhijithkm.shoppinglist.core.util.QuickAddParser.Result

class QuickAddParserTest {
    private fun parse(input: String) = QuickAddParser.parse(input)

    @Test
    fun `blank input is rejected`() {
        assertNull(parse(""))
        assertNull(parse("   "))
    }

    @Test
    fun `a plain name stays a name`() {
        assertEquals(Result("milk"), parse("milk"))
        assertEquals(Result("Brown bread"), parse("  Brown   bread "))
    }

    @Test
    fun `leading quantity and unit`() {
        assertEquals(Result("milk", 2.0, "kg"), parse("2 kg milk"))
        assertEquals(Result("sugar", 500.0, "g"), parse("500g sugar"))
        assertEquals(Result("eggs", 12.0, "pieces"), parse("12 pieces eggs"))
        assertEquals(Result("milk", 1.0, "litre"), parse("1 litre milk"))
        assertEquals(Result("milk", 2.0, "kg"), parse("2 KG milk"))
    }

    @Test
    fun `decimal quantities accept a dot or a comma`() {
        assertEquals(Result("oil", 1.5, "litres"), parse("1.5 litres oil"))
        assertEquals(Result("oil", 1.5, "litres"), parse("1,5 litres oil"))
    }

    @Test
    fun `a leading number without a unit is a quantity`() {
        assertEquals(Result("mangoes", 3.0, null), parse("3 mangoes"))
        assertEquals(Result("big apples", 2.0, null), parse("2 big apples"))
    }

    @Test
    fun `trailing quantity needs a known unit`() {
        assertEquals(Result("Milk", 2.0, "litres"), parse("Milk 2 litres"))
        assertEquals(Result("Rice", 5.0, "kg"), parse("Rice 5kg"))
        assertEquals(Result("Buy 2 apples"), parse("Buy 2 apples"))
    }

    @Test
    fun `names that merely contain digits stay intact`() {
        assertEquals(Result("7up"), parse("7up"))
        assertEquals(Result("2% milk"), parse("2% milk"))
        assertEquals(Result("Vitamin B 12"), parse("Vitamin B 12"))
        assertEquals(Result("Bread x2"), parse("Bread x2"))
    }

    @Test
    fun `a quantity and unit without a name is just a name`() {
        assertEquals(Result("2 kg"), parse("2 kg"))
        assertEquals(Result("2kg"), parse("2kg"))
    }

    @Test
    fun `invalid quantities are not treated as quantities`() {
        assertEquals(Result("0 apples"), parse("0 apples"))
        assertEquals(Result("1000001 kg rice"), parse("1000001 kg rice"))
    }
}
