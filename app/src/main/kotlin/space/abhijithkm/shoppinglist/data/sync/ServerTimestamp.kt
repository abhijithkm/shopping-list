package space.abhijithkm.shoppinglist.data.sync

import java.util.Locale

/**
 * AppHub's `updated_at` is a MySQL `DATETIME(3)` rendered as "YYYY-MM-DD HH:MM:SS.mmm" with no time zone.
 * The zone is whatever the server's session uses, so the value is only ever compared with other server
 * values (never with the device clock). These helpers turn it into a number whose *differences* are
 * correct milliseconds (a proleptic Gregorian day count, no zone, no daylight saving), without java.time
 * (not available below API 26).
 */
object ServerTimestamp {
    private val PATTERN = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?$""")

    /** Milliseconds on a zone-less scale, or null when [text] is not a timestamp. */
    fun parse(text: String): Long? {
        val g = PATTERN.matchEntire(text.trim())?.groupValues ?: return null
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].toInt()
        val millis = g[7].padEnd(3, '0').take(3).toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
        val days = daysFromCivil(year, month, day)
        return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L + millis
    }

    /** The timestamp [deltaMillis] earlier than [text] in the same format, or null when [text] is malformed. */
    fun minus(text: String, deltaMillis: Long): String? = parse(text)?.let { format(it - deltaMillis) }

    fun format(millis: Long): String {
        val totalSeconds = Math.floorDiv(millis, 1000L)
        val ms = Math.floorMod(millis, 1000L).toInt()
        val days = Math.floorDiv(totalSeconds, 86_400L)
        val secOfDay = Math.floorMod(totalSeconds, 86_400L).toInt()
        val (y, m, d) = civilFromDays(days)
        // Locale.ROOT: the default locale may print non-ASCII digits, which the parser (and the server) would not accept.
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d.%03d", y, m, d, secOfDay / 3600, secOfDay % 3600 / 60, secOfDay % 60, ms)
    }

    /** True when [a] is strictly earlier than [b]; unparseable values are never "earlier". */
    fun isBefore(a: String, b: String): Boolean {
        val pa = parse(a) ?: return false
        val pb = parse(b) ?: return false
        return pa < pb
    }

    /** The later of two timestamps (either may be null). */
    fun max(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        isBefore(a, b) -> b
        else -> a
    }

    // Howard Hinnant's civil-date algorithms (days since 1970-01-01).
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val doe = (z - era * 146_097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}
