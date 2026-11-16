package space.abhijithkm.shoppinglist.core.util

import java.math.BigDecimal
import java.math.RoundingMode

/** 2.0 becomes "2", 1.5 stays "1.5", 0.33333 becomes "0.333". Locale-independent so results are stable. */
fun formatQuantity(quantity: Double): String =
    BigDecimal.valueOf(quantity).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** "2 kg", "12 pieces", "3" or "kg"; null when there is neither a quantity nor a unit. */
fun formatQuantityAndUnit(quantity: Double?, unit: String?): String? {
    val cleanUnit = unit?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        quantity == null -> cleanUnit
        cleanUnit == null -> formatQuantity(quantity)
        else -> "${formatQuantity(quantity)} $cleanUnit"
    }
}
