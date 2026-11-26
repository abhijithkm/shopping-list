package space.abhijithkm.shoppinglist.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private suspend fun list(id: String = "l1") =
        db.listDao().upsert(ShoppingListEntity(id = id, name = id, createdAt = 1L, updatedAt = 1L))

    @Test
    fun upsertAndObserveReminder() = runTest {
        list()
        db.reminderDao().upsert(ReminderEntity(listId = "l1", repeat = ReminderRepeat.DAILY, hour = 18, minute = 30))

        val r = db.reminderDao().observe("l1").first()

        assertNotNull(r)
        assertEquals(ReminderRepeat.DAILY, r!!.repeat)
        assertEquals(18, r.hour)
        assertEquals(30, r.minute)
        assertTrue(r.enabled)
        assertTrue(r.onlyIfItemsRemain)
    }

    @Test
    fun purgingAListCascadesReminderAndPrefs() = runTest {
        list()
        db.reminderDao().upsert(ReminderEntity(listId = "l1"))
        db.listPrefsDao().upsert(ListPrefsEntity(listId = "l1", shoppingMode = true))

        db.listDao().purge("l1")

        assertNull(db.reminderDao().get("l1"))
        assertEquals(emptyList<String>(), db.listPrefsDao().shoppingModeListIds())
    }

    @Test
    fun softDeleteKeepsReminderRowSoARestoredListGetsItBack() = runTest {
        list()
        db.reminderDao().upsert(ReminderEntity(listId = "l1"))

        db.listDao().softDelete("l1", 5L)

        assertNotNull(db.reminderDao().get("l1"))
    }

    @Test
    fun allEnabledSkipsDisabledReminders() = runTest {
        list("a")
        list("b")
        db.reminderDao().upsert(ReminderEntity(listId = "a"))
        db.reminderDao().upsert(ReminderEntity(listId = "b", enabled = false))

        assertEquals(listOf("a"), db.reminderDao().allEnabled().map { it.listId })
    }

    @Test
    fun allReturnsDisabledRemindersToo() = runTest {
        list("a")
        list("b")
        db.reminderDao().upsert(ReminderEntity(listId = "a"))
        db.reminderDao().upsert(ReminderEntity(listId = "b", enabled = false))

        assertEquals(listOf("a", "b"), db.reminderDao().all().map { it.listId }.sorted())
    }

    @Test
    fun markFiredAndDisableUpdateTheRow() = runTest {
        list()
        db.reminderDao().upsert(ReminderEntity(listId = "l1"))

        db.reminderDao().markFired("l1", 99L)
        db.reminderDao().disable("l1")

        val r = db.reminderDao().get("l1")!!
        assertEquals(99L, r.lastFiredAt)
        assertFalse(r.enabled)
    }

    @Test
    fun shoppingModeListIdsOnlyReturnsListsWithTheSwitchOn() = runTest {
        list("a")
        list("b")
        db.listPrefsDao().upsert(ListPrefsEntity(listId = "a", shoppingMode = true))
        db.listPrefsDao().upsert(ListPrefsEntity(listId = "b", shoppingMode = false))

        assertEquals(listOf("a"), db.listPrefsDao().shoppingModeListIds())
    }
}
