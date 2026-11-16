package space.abhijithkm.shoppinglist.data.sync

/** Retry delays for a row that keeps failing: 5 s, 30 s, 2 min, 10 min, then 30 min. */
object Backoff {
    private val STEPS_MS = longArrayOf(5_000, 30_000, 120_000, 600_000, 1_800_000)

    /** Delay before the next attempt after [failCount] failures (the first failure is 1). */
    fun delayMs(failCount: Int): Long = STEPS_MS[(failCount - 1).coerceIn(0, STEPS_MS.lastIndex)]
}
