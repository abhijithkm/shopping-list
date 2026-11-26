package space.abhijithkm.shoppinglist.surfaces

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.ListPrefsEntity
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity

/** The updater listens to Room itself, so every writer of the four tables triggers a refresh, whoever it is. */
@RunWith(AndroidJUnit4::class)
class SurfaceUpdaterWritePathsTest {
    private lateinit var db: AppDatabase
    private val refreshes = AtomicInteger()
    private lateinit var job: Job

    @Before
    fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val refresher = object : SurfaceRefresher {
            override suspend fun refreshAll() {
                refreshes.incrementAndGet()
            }
        }
        job = SurfaceUpdater(RoomChangeSource(db), refresher).start(CoroutineScope(Dispatchers.Default), debounceMs = 50)
        awaitCount(1) // Room announces the current state once when the collection starts.
    }

    @After
    fun close() {
        job.cancel()
        db.close()
    }

    private suspend fun awaitCount(expected: Int) = withTimeout(5_000) {
        while (refreshes.get() < expected) delay(20)
    }

    private suspend fun settle() = delay(400)

    private suspend fun list(id: String = "l1") =
        db.listDao().upsert(ShoppingListEntity(id = id, name = id, createdAt = 1L, updatedAt = 1L))

    @Test
    fun anItemInsertTriggersARefresh() = runBlocking {
        list()
        awaitCount(2)
        settle()
        val before = refreshes.get()

        db.itemDao().upsert(ShoppingItemEntity(id = "i1", listId = "l1", name = "Milk", createdAt = 1L, updatedAt = 1L))

        awaitCount(before + 1)
    }

    @Test
    fun aListSoftDeleteTriggersARefresh() = runBlocking {
        list()
        settle()
        val before = refreshes.get()

        db.listDao().softDelete("l1", 5L)

        awaitCount(before + 1)
    }

    @Test
    fun aReminderChangeTriggersARefresh() = runBlocking {
        list()
        settle()
        val before = refreshes.get()

        db.reminderDao().upsert(ReminderEntity(listId = "l1"))

        awaitCount(before + 1)
    }

    @Test
    fun aShoppingModeSwitchTriggersARefresh() = runBlocking {
        list()
        settle()
        val before = refreshes.get()

        db.listPrefsDao().upsert(ListPrefsEntity(listId = "l1", shoppingMode = true))

        awaitCount(before + 1)
    }

    @Test
    fun aMultiTableTransactionRefreshesOnce() = runBlocking {
        settle()
        val before = refreshes.get()

        db.withTransaction {
            list("t1")
            db.itemDao().upsert(ShoppingItemEntity(id = "i1", listId = "t1", name = "Milk", createdAt = 1L, updatedAt = 1L))
            db.itemDao().upsert(ShoppingItemEntity(id = "i2", listId = "t1", name = "Eggs", createdAt = 1L, updatedAt = 1L))
        }
        awaitCount(before + 1)
        settle()

        assertEquals(before + 1, refreshes.get())
    }
}
