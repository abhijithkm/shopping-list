package space.abhijithkm.shoppinglist.core.model

/**
 * The built-in categories. They are fixed keys, not database rows, so they are identical on every
 * device and need no sync. Custom categories (Phase 2) are stored separately.
 * Categories are always optional on an item.
 */
enum class DefaultCategory(val key: String, val colorArgb: Long) {
    VEGETABLES("vegetables", 0xFF4CAF50),
    FRUITS("fruits", 0xFFFF7043),
    DAIRY("dairy", 0xFF42A5F5),
    MEAT_FISH("meat_fish", 0xFFE53935),
    BAKERY("bakery", 0xFFD4A24C),
    GROCERIES("groceries", 0xFF8D6E63),
    BEVERAGES("beverages", 0xFF26C6DA),
    HOUSEHOLD("household", 0xFF7E57C2),
    PERSONAL_CARE("personal_care", 0xFFEC407A),
    MEDICINES("medicines", 0xFF26A69A),
    OTHERS("others", 0xFF78909C),
    ;

    companion object {
        fun fromKey(key: String?): DefaultCategory? = entries.firstOrNull { it.key == key }
    }
}
