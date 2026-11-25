package space.abhijithkm.shoppinglist.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat

/**
 * Pure date maths for reminders (no Android, no clock). Occurrences are computed in the given [ZoneId], so a
 * time-zone change simply recomputes them. A time inside a daylight-saving gap moves to the first valid time after it;
 * a time that happens twice fires at the first of the two.
 */
object ReminderMath {
    /** A reminder missed while the phone was off still fires once at boot when it was at most this late. */
    const val GRACE_MS = 24L * 60 * 60 * 1000

    /** How many days ahead or back to look for a weekly occurrence (a whole week plus one). */
    private const val SEARCH_DAYS = 8

    /** The first occurrence strictly after [now], or null (disabled, one-time already past, weekly with no day chosen). */
    fun nextFire(now: Long, zone: ZoneId, r: ReminderEntity): Long? {
        if (!r.enabled) return null
        if (r.repeat == ReminderRepeat.NONE) return r.oneTimeAtMillis?.takeIf { it > now }
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
        for (offset in 0..SEARCH_DAYS) {
            val date = today.plusDays(offset.toLong())
            if (!allowed(date, r)) continue
            val at = occurrence(date, r, zone)
            if (at > now) return at
        }
        return null
    }

    /** The latest occurrence at or before [now], or null. The caller applies [GRACE_MS] and `lastFiredAt`. */
    fun lastOccurrenceAtOrBefore(now: Long, zone: ZoneId, r: ReminderEntity): Long? {
        if (!r.enabled) return null
        if (r.repeat == ReminderRepeat.NONE) return r.oneTimeAtMillis?.takeIf { it <= now }
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
        for (offset in 0..SEARCH_DAYS) {
            val date = today.minusDays(offset.toLong())
            if (!allowed(date, r)) continue
            val at = occurrence(date, r, zone)
            if (at <= now) return at
        }
        return null
    }

    /** Daily: every day. Weekly: the days in the mask (bit 0 = Monday ... bit 6 = Sunday). */
    private fun allowed(date: LocalDate, r: ReminderEntity): Boolean = when (r.repeat) {
        ReminderRepeat.DAILY -> true
        ReminderRepeat.WEEKLY -> r.daysOfWeekMask and (1 shl (date.dayOfWeek.value - 1)) != 0
        ReminderRepeat.NONE -> false
    }

    private fun occurrence(date: LocalDate, r: ReminderEntity, zone: ZoneId): Long =
        ZonedDateTime.ofLocal(LocalDateTime.of(date, LocalTime.of(r.hour, r.minute)), zone, null).toInstant().toEpochMilli()
}
