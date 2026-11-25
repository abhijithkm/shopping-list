package space.abhijithkm.shoppinglist.reminders

import android.app.PendingIntent
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.core.util.DeviceClock
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity
import space.abhijithkm.shoppinglist.surfaces.SurfaceNotifier

/**
 * Inexact alarms cannot be listed through any API, so these tests ask the system whether the alarm's PendingIntent exists
 * (FLAG_NO_CREATE returns null when it does not): it exists exactly while the alarm is scheduled.
 */
@RunWith(AndroidJUnit4::class)
class ReminderAlarmsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = AlarmManagerGateway(context)
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        gateway.cancel("l1")
        gateway.cancel("l2")
    }

    @After
    fun tearDown() {
        gateway.cancel("l1")
        gateway.cancel("l2")
        db.close()
    }

    private fun alarmExists(listId: String) = PendingIntent.getBroadcast(
        context,
        0,
        AlarmManagerGateway.alarmIntent(context, listId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    ) != null

    private fun scheduler() = ReminderScheduler(
        db.reminderDao(),
        db.listDao(),
        db.itemDao(),
        gateway,
        DeviceClock(),
        { ZoneId.systemDefault() },
        SurfaceNotifier(context),
    )

    @Test
    fun scheduleCreatesTheAlarmAndCancelRemovesIt() {
        assertFalse(alarmExists("l1"))

        gateway.schedule("l1", System.currentTimeMillis() + 3_600_000)
        assertTrue(alarmExists("l1"))

        gateway.cancel("l1")
        assertFalse(alarmExists("l1"))
    }

    @Test
    fun twoListsGetTwoSeparateAlarms() {
        gateway.schedule("l1", System.currentTimeMillis() + 3_600_000)
        gateway.schedule("l2", System.currentTimeMillis() + 3_600_000)

        gateway.cancel("l1")

        assertFalse(alarmExists("l1"))
        assertTrue(alarmExists("l2"))
    }

    @Test
    fun rescheduleAllRebuildsTheAlarmFromRoomAndDropsItForADeletedList() = runTest {
        db.listDao().upsert(ShoppingListEntity(id = "l1", name = "Groceries", createdAt = 1L, updatedAt = 1L))
        db.reminderDao().upsert(ReminderEntity(listId = "l1", repeat = ReminderRepeat.DAILY, hour = 9, minute = 0))
        assertFalse(alarmExists("l1"))

        scheduler().rescheduleAll(catchUp = false) // what a boot broadcast does
        assertTrue(alarmExists("l1"))

        db.listDao().softDelete("l1", 5L)
        scheduler().rescheduleAll(catchUp = false)
        assertFalse(alarmExists("l1"))
    }
}
