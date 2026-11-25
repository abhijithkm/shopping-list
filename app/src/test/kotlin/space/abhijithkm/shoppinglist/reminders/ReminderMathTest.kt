package space.abhijithkm.shoppinglist.reminders

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat

class ReminderMathTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun local(zone: ZoneId, millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)

    private fun daily(hour: Int, minute: Int) = ReminderEntity(listId = "l", repeat = ReminderRepeat.DAILY, hour = hour, minute = minute)

    private fun weekly(mask: Int, hour: Int = 9, minute: Int = 0) =
        ReminderEntity(listId = "l", repeat = ReminderRepeat.WEEKLY, hour = hour, minute = minute, daysOfWeekMask = mask)

    @Test
    fun dailyTodayIfTimeNotPassed() {
        val now = at(kolkata, 2027, 1, 10, 8, 0)
        assertEquals(at(kolkata, 2027, 1, 10, 18, 30), ReminderMath.nextFire(now, kolkata, daily(18, 30)))
    }

    @Test
    fun dailyTomorrowIfPassed() {
        val now = at(kolkata, 2027, 1, 10, 19, 0)
        assertEquals(at(kolkata, 2027, 1, 11, 18, 30), ReminderMath.nextFire(now, kolkata, daily(18, 30)))
    }

    @Test
    fun dailyExactlyNowMovesToTomorrow() {
        val now = at(kolkata, 2027, 1, 10, 18, 30)
        assertEquals(at(kolkata, 2027, 1, 11, 18, 30), ReminderMath.nextFire(now, kolkata, daily(18, 30)))
    }

    @Test
    fun weeklyPicksNextMaskedDay() {
        // Monday (bit 0) and Thursday (bit 3); 2027-01-08 is a Friday, so the next one is Monday the 11th.
        val now = at(kolkata, 2027, 1, 8, 10, 0)
        assertEquals(at(kolkata, 2027, 1, 11, 9, 0), ReminderMath.nextFire(now, kolkata, weekly(0b0001001)))
    }

    @Test
    fun weeklySameDayLaterTimeStillCounts() {
        // 2027-01-07 is a Thursday.
        val now = at(kolkata, 2027, 1, 7, 8, 0)
        assertEquals(at(kolkata, 2027, 1, 7, 18, 0), ReminderMath.nextFire(now, kolkata, weekly(0b0001000, hour = 18)))
    }

    @Test
    fun weeklyEmptyMaskIsNull() {
        assertNull(ReminderMath.nextFire(at(kolkata, 2027, 1, 8, 10, 0), kolkata, weekly(0)))
    }

    @Test
    fun oneTimeInFutureReturnsIt() {
        val now = at(kolkata, 2027, 1, 8, 10, 0)
        val r = ReminderEntity(listId = "l", repeat = ReminderRepeat.NONE, oneTimeAtMillis = now + 1000)
        assertEquals(now + 1000, ReminderMath.nextFire(now, kolkata, r))
    }

    @Test
    fun oneTimeInPastIsNull() {
        val now = at(kolkata, 2027, 1, 8, 10, 0)
        val r = ReminderEntity(listId = "l", repeat = ReminderRepeat.NONE, oneTimeAtMillis = now - 1)
        assertNull(ReminderMath.nextFire(now, kolkata, r))
    }

    @Test
    fun oneTimeWithoutATimeIsNull() {
        assertNull(ReminderMath.nextFire(0L, kolkata, ReminderEntity(listId = "l", repeat = ReminderRepeat.NONE)))
    }

    @Test
    fun disabledIsNull() {
        assertNull(ReminderMath.nextFire(0L, kolkata, daily(9, 0).copy(enabled = false)))
    }

    @Test
    fun dstGapDailyFiresOnceAtTheNextValidTime() {
        // Berlin skips 02:00 to 03:00 on 2027-03-28: a 02:30 reminder moves to 03:30 that day and is not repeated.
        val r = daily(2, 30)
        val first = ReminderMath.nextFire(at(berlin, 2027, 3, 27, 3, 0), berlin, r)!!
        assertEquals(at(berlin, 2027, 3, 28, 3, 30), first)
        assertEquals(at(berlin, 2027, 3, 29, 2, 30), ReminderMath.nextFire(first, berlin, r))
    }

    @Test
    fun dstOverlapFiresOnce() {
        // Berlin repeats 02:00 to 03:00 on 2027-10-31: 02:30 happens twice, the reminder fires at the first one only.
        val r = daily(2, 30)
        val first = ReminderMath.nextFire(at(berlin, 2027, 10, 30, 12, 0), berlin, r)!!
        assertEquals(31, local(berlin, first).dayOfMonth)
        assertEquals(2, local(berlin, first).hour)
        val second = ReminderMath.nextFire(first, berlin, r)!!
        assertEquals(11, local(berlin, second).monthValue)
        assertEquals(1, local(berlin, second).dayOfMonth)
    }

    @Test
    fun zoneChangeRecomputesTheLocalTime() {
        val now = at(kolkata, 2027, 1, 10, 8, 0)
        val inKolkata = ReminderMath.nextFire(now, kolkata, daily(18, 30))!!
        val inNewYork = ReminderMath.nextFire(now, newYork, daily(18, 30))!!
        assertNotEquals(inKolkata, inNewYork)
        assertEquals(18, local(kolkata, inKolkata).hour)
        assertEquals(18, local(newYork, inNewYork).hour)
        assertEquals(30, local(newYork, inNewYork).minute)
    }

    @Test
    fun lastOccurrenceIncludesExactlyNow() {
        val now = at(kolkata, 2027, 1, 10, 18, 30)
        assertEquals(now, ReminderMath.lastOccurrenceAtOrBefore(now, kolkata, daily(18, 30)))
    }

    @Test
    fun lastOccurrenceBeforeTodaysTimeIsYesterday() {
        val now = at(kolkata, 2027, 1, 10, 18, 29)
        assertEquals(at(kolkata, 2027, 1, 9, 18, 30), ReminderMath.lastOccurrenceAtOrBefore(now, kolkata, daily(18, 30)))
    }

    @Test
    fun lastOccurrenceWeeklyWalksBackToAMaskedDay() {
        // Wednesday 2027-01-13 with Monday and Thursday: the last one was Monday the 11th.
        val now = at(kolkata, 2027, 1, 13, 12, 0)
        assertEquals(at(kolkata, 2027, 1, 11, 9, 0), ReminderMath.lastOccurrenceAtOrBefore(now, kolkata, weekly(0b0001001)))
    }

    @Test
    fun lastOccurrenceOneTimeOnlyOnceItHasPassed() {
        val t = at(kolkata, 2027, 1, 10, 18, 30)
        val r = ReminderEntity(listId = "l", repeat = ReminderRepeat.NONE, oneTimeAtMillis = t)
        assertEquals(t, ReminderMath.lastOccurrenceAtOrBefore(t, kolkata, r))
        assertNull(ReminderMath.lastOccurrenceAtOrBefore(t - 1, kolkata, r))
    }

    @Test
    fun lastOccurrenceOfADisabledReminderIsNull() {
        assertNull(ReminderMath.lastOccurrenceAtOrBefore(Long.MAX_VALUE / 4, kolkata, daily(9, 0).copy(enabled = false)))
    }
}
