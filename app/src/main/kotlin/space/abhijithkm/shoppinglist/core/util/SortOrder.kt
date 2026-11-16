package space.abhijithkm.shoppinglist.core.util

/**
 * Manual ordering of items uses gap-based keys so dragging one item rewrites only that item's row
 * (which also means one sync operation later). When two neighbours get too close, the list is
 * renumbered once.
 */
object SortOrder {
    const val STEP = 1024.0
    private const val MIN_GAP = 1e-6

    /** Key for an item appended after [last]. */
    fun after(last: Double?): Double = (last ?: 0.0) + STEP

    /**
     * Key for an item placed between [above] and [below] (either may be absent). Returns null when
     * the gap is too small to split, which tells the caller to renumber first.
     */
    fun between(above: Double?, below: Double?): Double? = when {
        above == null && below == null -> STEP
        above == null -> below!! - STEP
        below == null -> above + STEP
        below - above < 2 * MIN_GAP -> null
        else -> (above + below) / 2
    }

    /** Evenly spaced keys for [count] items. */
    fun renumbered(count: Int): List<Double> = List(count) { (it + 1) * STEP }
}
