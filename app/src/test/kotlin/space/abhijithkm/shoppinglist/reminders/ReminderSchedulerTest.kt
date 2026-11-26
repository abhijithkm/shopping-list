package space.abhijithkm.shoppinglist.reminders

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.local.ItemDao
import space.abhijithkm.shoppinglist.data.local.ReminderDao
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity
import space.abhijithkm.shoppinglist.data.repository.NotificationPrefs
import space.abhijithkm.shoppinglist.surfaces.SurfaceNotifier

class ReminderSchedulerTest {
    private val zone = ZoneId.of("UTC")

    private fun utc(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2027, 1, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private class FakeClock(var now: Long) : Clock {
        override fun nowMillis() = now
    }

    private class RecordingGateway : AlarmGateway {
        val scheduled = mutableMapOf<String, Long>()
        val cancelled = mutableListOf<String>()

        override fun schedule(listId: String, atMillis: Long) {
            scheduled[listId] = atMillis
        }

        override fun cancel(listId: String) {
            cancelled += listId
            scheduled.remove(listId)
        }
    }

    private val reminders = mockk<ReminderDao>(relaxed = true)
    private val lists = mockk<ShoppingListDao>()
    private val items = mockk<ItemDao>()
    private val notifier = mockk<SurfaceNotifier>(relaxed = true)
    private val gateway = RecordingGateway()
    private val clock = FakeClock(utc(10, 8))

    private var remindersOn = true

    private val prefs = object : NotificationPrefs {
        override suspend fun remindersEnabled() = remindersOn

        override suspend fun shoppingModeEnabled() = true
    }

    private fun scheduler() = ReminderScheduler(reminders, lists, items, gateway, clock, { zone }, notifier, prefs)

    private fun list(archived: Boolean = false, deletedAt: Long? = null) =
        ShoppingListEntity(id = "l1", name = "Groceries", isArchived = archived, createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt)

    private fun daily(hour: Int = 9, minute: Int = 0, onlyIfItemsRemain: Boolean = true, lastFiredAt: Long? = null) =
        ReminderEntity(listId = "l1", repeat = ReminderRepeat.DAILY, hour = hour, minute = minute, onlyIfItemsRemain = onlyIfItemsRemain, lastFiredAt = lastFiredAt)

    private fun openItems(n: Int) = List(n) { ShoppingItemEntity(id = "i$it", listId = "l1", name = "Item $it", createdAt = 1L, updatedAt = 1L) }

    private fun canNotify(value: Boolean) {
        every { notifier.canNotify() } returns value
    }

    private fun given(reminder: ReminderEntity, list: ShoppingListEntity? = list(), open: Int = 2) {
        coEvery { reminders.all() } returns listOf(reminder)
        coEvery { reminders.get("l1") } returns reminder
        coEvery { lists.getById("l1") } returns list
        coEvery { items.openItemsInOrder("l1") } returns openItems(open)
        canNotify(true)
    }

    @Test
    fun schedulesTheNextFireForAnEnabledReminder() = runTest {
        given(daily(9))

        scheduler().rescheduleAll(catchUp = false)

        assertEquals(utc(10, 9), gateway.scheduled["l1"])
    }

    @Test
    fun cancelsWhenTheListIsDeleted() = runTest {
        given(daily(9), list(deletedAt = 5L))

        scheduler().rescheduleAll(catchUp = false)

        assertTrue("l1" in gateway.cancelled)
        assertFalse("l1" in gateway.scheduled)
    }

    @Test
    fun cancelsWhenTheListIsArchived() = runTest {
        given(daily(9), list(archived = true))

        scheduler().rescheduleAll(catchUp = false)

        assertTrue("l1" in gateway.cancelled)
    }

    @Test
    fun aRestoredListRearmsItsReminder() = runTest {
        given(daily(9), list(deletedAt = 5L))
        scheduler().rescheduleAll(catchUp = false)
        assertFalse("l1" in gateway.scheduled)

        coEvery { lists.getById("l1") } returns list()
        scheduler().rescheduleAll(catchUp = false)

        assertEquals(utc(10, 9), gateway.scheduled["l1"])
    }

    @Test
    fun cancelsADisabledReminder() = runTest {
        given(daily(9).copy(enabled = false))

        scheduler().rescheduleAll(catchUp = false)

        assertTrue("l1" in gateway.cancelled)
    }

    @Test
    fun skipsWhenNothingIsLeftAndTheFlagIsOn() = runTest {
        given(daily(9), open = 0)

        assertEquals(ReminderDecision.Skip, scheduler().onFired("l1"))
    }

    @Test
    fun postsWhenItemsAreLeft() = runTest {
        given(daily(9), open = 3)

        assertEquals(ReminderDecision.Post("Groceries", 3), scheduler().onFired("l1"))
    }

    @Test
    fun postsWithZeroItemsWhenTheFlagIsOff() = runTest {
        given(daily(9, onlyIfItemsRemain = false), open = 0)

        assertEquals(ReminderDecision.Post("Groceries", 0), scheduler().onFired("l1"))
    }

    @Test
    fun skipsADeletedList() = runTest {
        given(daily(9), list(deletedAt = 5L))

        assertEquals(ReminderDecision.Skip, scheduler().onFired("l1"))
    }

    @Test
    fun aOneTimeReminderIsDisabledAfterItFires() = runTest {
        val once = ReminderEntity(listId = "l1", repeat = ReminderRepeat.NONE, oneTimeAtMillis = clock.now - 1)
        given(once)

        scheduler().onFired("l1")

        coVerify { reminders.disable("l1") }
        assertTrue("l1" in gateway.cancelled)
    }

    @Test
    fun aRepeatingReminderSchedulesItsNextFireAndRecordsTheFire() = runTest {
        clock.now = utc(10, 9, 5)
        given(daily(9))

        scheduler().onFired("l1")

        coVerify { reminders.markFired("l1", utc(10, 9, 5)) }
        assertEquals(utc(11, 9), gateway.scheduled["l1"])
    }

    @Test
    fun bootCatchUpFiresOnceForAMissedOccurrenceWithinTheGrace() = runTest {
        clock.now = utc(10, 10) // 09:00 passed an hour ago while the phone was off
        given(daily(9), open = 2)

        scheduler().rescheduleAll(catchUp = true)

        verify(exactly = 1) { notifier.postReminder("l1", "Groceries", 2) }
        assertEquals(utc(11, 9), gateway.scheduled["l1"])
    }

    @Test
    fun bootCatchUpIgnoresAnOccurrenceOlderThanTheGrace() = runTest {
        // Monday-only reminder; 2027-01-10 is a Sunday, the last Monday (the 4th) is 6 days ago.
        clock.now = utc(10, 10)
        given(ReminderEntity(listId = "l1", repeat = ReminderRepeat.WEEKLY, hour = 9, daysOfWeekMask = 0b0000001))

        scheduler().rescheduleAll(catchUp = true)

        verify(exactly = 0) { notifier.postReminder(any(), any(), any()) }
    }

    @Test
    fun bootCatchUpDoesNotRepeatAReminderThatAlreadyFired() = runTest {
        clock.now = utc(10, 10)
        given(daily(9, lastFiredAt = utc(10, 9, 5)))

        scheduler().rescheduleAll(catchUp = true)

        verify(exactly = 0) { notifier.postReminder(any(), any(), any()) }
    }

    @Test
    fun aTimeChangeDoesNotCatchUp() = runTest {
        clock.now = utc(10, 10)
        given(daily(9))

        scheduler().rescheduleAll(catchUp = false)

        verify(exactly = 0) { notifier.postReminder(any(), any(), any()) }
    }

    @Test
    fun aDueButUndeliveredReminderIsReArmedAtOnceNotPushedToTomorrow() = runTest {
        // The 09:00 alarm is late (inexact alarms can be); a tick at 09:00:10 wrote to the database and re-ran the scheduling.
        clock.now = utc(10, 9, 0) + 10_000L
        given(daily(9, lastFiredAt = utc(9, 9, 5)))

        scheduler().rescheduleAll(catchUp = false)

        assertEquals(clock.now + ReminderScheduler.DUE_REARM_DELAY_MS, gateway.scheduled["l1"])
    }

    @Test
    fun aDueReminderThatAlreadyFiredIsScheduledForTomorrow() = runTest {
        clock.now = utc(10, 9, 0) + 10_000L
        given(daily(9, lastFiredAt = utc(10, 9, 0) + 5_000L))

        scheduler().rescheduleAll(catchUp = false)

        assertEquals(utc(11, 9), gateway.scheduled["l1"])
    }

    @Test
    fun anOccurrenceOlderThanTheDueWindowIsNotReArmed() = runTest {
        clock.now = utc(10, 9, 0) + ReminderScheduler.DUE_WINDOW_MS + 60_000L
        given(daily(9, lastFiredAt = utc(9, 9, 5)))

        scheduler().rescheduleAll(catchUp = false)

        assertEquals(utc(11, 9), gateway.scheduled["l1"])
    }

    @Test
    fun aDueOneTimeReminderIsReArmedToo() = runTest {
        clock.now = utc(10, 9, 0) + 30_000L
        given(ReminderEntity(listId = "l1", repeat = ReminderRepeat.NONE, oneTimeAtMillis = utc(10, 9, 0)))

        scheduler().rescheduleAll(catchUp = false)

        assertEquals(clock.now + ReminderScheduler.DUE_REARM_DELAY_MS, gateway.scheduled["l1"])
    }

    @Test
    fun theGlobalRemindersSwitchOffDoesNotPostButStillSchedulesTheNext() = runTest {
        remindersOn = false
        clock.now = utc(10, 9, 5)
        given(daily(9), open = 2)

        scheduler().fire("l1")

        verify(exactly = 0) { notifier.postReminder(any(), any(), any()) }
        assertEquals(utc(11, 9), gateway.scheduled["l1"])
    }

    @Test
    fun deniedPermissionDoesNotPost() = runTest {
        given(daily(9), open = 2)
        canNotify(false)

        scheduler().fire("l1")

        verify(exactly = 0) { notifier.postReminder(any(), any(), any()) }
    }
}
