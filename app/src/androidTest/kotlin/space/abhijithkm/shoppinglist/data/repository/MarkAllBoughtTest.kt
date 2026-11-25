package space.abhijithkm.shoppinglist.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat

private class TickClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis() = now
}

private class TickIds : IdGenerator {
    private var n = 0
    override fun newId() = "id-${++n}"
}

@RunWith(AndroidJUnit4::class)
class MarkAllBoughtTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LocalShoppingRepository
    private lateinit var prefs: SurfacePrefsRepository
    private val clock = TickClock()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = LocalShoppingRepository(db, clock, TickIds())
        prefs = SurfacePrefsRepository(db.reminderDao(), db.listPrefsDao())
    }

    @After
    fun close() = db.close()

    private fun accountRepo() = LocalShoppingRepository(db, clock, TickIds(), AccountScope { "acc" })

    @Test
    fun markAllBoughtTicksOnlyOpenItemsAndBumpsTheirRevision() = runTest {
        val list = repo.createList("Groceries")
        val milk = repo.addItem(list, "milk")!!
        val eggs = repo.addItem(list, "eggs")!!
        val bread = repo.addItem(list, "bread")!!
        repo.setItemBought(bread, true)
        val breadBefore = db.itemDao().getById(bread)!!

        clock.now = 5_000L
        repo.markAllBought(list)

        val milkAfter = db.itemDao().getById(milk)!!
        val eggsAfter = db.itemDao().getById(eggs)!!
        val breadAfter = db.itemDao().getById(bread)!!
        assertTrue(milkAfter.isBought && eggsAfter.isBought && breadAfter.isBought)
        assertEquals(5_000L, milkAfter.boughtAt)
        assertEquals(1, milkAfter.revision)
        assertEquals(breadBefore.revision, breadAfter.revision)
        assertEquals(breadBefore.boughtAt, breadAfter.boughtAt)
    }

    @Test
    fun markAllBoughtLeavesDeletedItemsAlone() = runTest {
        val list = repo.createList("Groceries")
        val gone = repo.addItem(list, "milk")!!
        repo.addItem(list, "eggs")
        repo.deleteItem(gone)

        repo.markAllBought(list)

        assertFalse(db.itemDao().getById(gone)!!.isBought)
    }

    @Test
    fun markAllBoughtWhenNothingIsOpenDoesNotTouchTheList() = runTest {
        val list = repo.createList("Empty")
        val before = db.listDao().getById(list)!!

        clock.now = 9_000L
        repo.markAllBought(list)

        val after = db.listDao().getById(list)!!
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.revision, after.revision)
    }

    @Test
    fun markAllBoughtMakesAccountItemsDirtyForSync() = runTest {
        val account = accountRepo()
        val list = account.createList("Mine")
        val item = account.addItem(list, "milk")!!
        db.syncDao().markItemClean(item, db.itemDao().getById(item)!!.revision)

        account.markAllBought(list)

        val after = db.itemDao().getById(item)!!
        assertTrue(after.revision > after.serverRevision)
    }

    @Test
    fun setShoppingModeUpsertsAndObserves() = runTest {
        val list = repo.createList("Groceries")
        assertFalse(prefs.observeShoppingMode(list).first())

        prefs.setShoppingMode(list, true)
        assertTrue(prefs.observeShoppingMode(list).first())

        prefs.setShoppingMode(list, false)
        assertFalse(prefs.observeShoppingMode(list).first())
    }

    @Test
    fun saveReminderReplacesTheExistingOne() = runTest {
        val list = repo.createList("Groceries")
        prefs.saveReminder(ReminderEntity(listId = list, repeat = ReminderRepeat.DAILY, hour = 8))
        prefs.saveReminder(ReminderEntity(listId = list, repeat = ReminderRepeat.WEEKLY, hour = 18, daysOfWeekMask = 0b0000001))

        val saved = prefs.observeReminder(list).first()!!

        assertEquals(ReminderRepeat.WEEKLY, saved.repeat)
        assertEquals(18, saved.hour)
        assertEquals(1, db.reminderDao().allEnabled().size)
    }

    @Test
    fun disableReminderKeepsTheRowButTurnsItOff() = runTest {
        val list = repo.createList("Groceries")
        prefs.saveReminder(ReminderEntity(listId = list))

        prefs.disableReminder(list)

        assertFalse(prefs.observeReminder(list).first()!!.enabled)
    }
}
