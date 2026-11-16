package space.abhijithkm.shoppinglist.core.util

import space.abhijithkm.shoppinglist.core.model.Limits

/**
 * Turns one line typed into the quick-add field into an item: "2 kg milk", "500g sugar",
 * "Milk 2 litres" and "eggs" all work. A quantity is only recognised next to a known unit or at
 * the very start ("3 mangoes"), so names such as "7up", "2% milk" or "Vitamin B 12" stay intact.
 */
object QuickAddParser {
    data class Result(val name: String, val quantity: Double? = null, val unit: String? = null)

    private val UNITS = setOf(
        "kg", "g", "gm", "l", "ltr", "litre", "litres", "liter", "liters", "ml",
        "pc", "pcs", "piece", "pieces", "pack", "packs", "packet", "packets",
        "bottle", "bottles", "dozen", "can", "cans", "box", "boxes", "bag", "bags",
    )

    private const val NUMBER = """\d+(?:[.,]\d+)?"""
    private val LEADING = Regex("""^($NUMBER)\s*([A-Za-z]+)?\s+(.+)$""")
    private val TRAILING = Regex("""^(.+?)\s+($NUMBER)\s*([A-Za-z]+)$""")
    private val WHITESPACE = Regex("""\s+""")

    /** Returns null when [input] is blank. */
    fun parse(input: String): Result? {
        val text = input.trim().replace(WHITESPACE, " ")
        if (text.isEmpty()) return null

        LEADING.matchEntire(text)?.let { m ->
            val quantity = quantityOf(m.groupValues[1])
            val candidateUnit = m.groups[2]?.value
            val rest = m.groupValues[3].trim()
            if (quantity != null && rest.isNotEmpty()) {
                if (candidateUnit != null && candidateUnit.lowercase() in UNITS) {
                    return Result(rest, quantity, candidateUnit.lowercase())
                }
                // "2 big apples": the word after the number is part of the name, not a unit.
                // "2 kg" alone has no name, so it falls through and stays a plain name.
                if (candidateUnit != null || rest.lowercase() !in UNITS) {
                    val name = listOfNotNull(candidateUnit, rest).joinToString(" ")
                    return Result(name, quantity, null)
                }
            }
        }

        TRAILING.matchEntire(text)?.let { m ->
            val quantity = quantityOf(m.groupValues[2])
            val unit = m.groupValues[3].lowercase()
            if (quantity != null && unit in UNITS) {
                return Result(m.groupValues[1].trim(), quantity, unit)
            }
        }

        return Result(text)
    }

    private fun quantityOf(raw: String): Double? =
        raw.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it <= Limits.MAX_QUANTITY }
}
