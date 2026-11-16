package space.abhijithkm.shoppinglist.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
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

    private fun list(
        id: String,
        pinned: Boolean = false,
        updatedAt: Long = 1L,
        archived: Boolean = false,
        deletedAt: Long? = null,
    ) = ShoppingListEntity(
        id = id,
        name = id,
        isPinned = pinned,
        isArchived = archived,
        createdAt = 1L,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun item(
        id: String,
        listId: String,
        bought: Boolean = false,
        sortOrder: Double = 0.0,
        deletedAt: Long? = null,
    ) = ShoppingItemEntity(
        id = id,
        listId = listId,
        name = id,
        isBought = bought,
        sortOrder = sortOrder,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = deletedAt,
    )

    @Test
    fun countsIgnoreDeletedItems() = runTest {
        db.listDao().upsert(list("groceries"))
        db.itemDao().upsert(item("milk", "groceries", bought = true))
        db.itemDao().upsert(item("eggs", "groceries"))
        db.itemDao().upsert(item("old", "groceries", deletedAt = 5L))

        val row = db.listDao().observeActiveWithCounts().first().single()
        assertEquals(2, row.totalCount)
        assertEquals(1, row.boughtCount)
    }

    @Test
    fun pinnedListsFirstThenMostRecentlyUpdated() = runTest {
        db.listDao().upsert(list("old", updatedAt = 1L))
        db.listDao().upsert(list("new", updatedAt = 9L))
        db.listDao().upsert(list("pinned", pinned = true, updatedAt = 2L))

        val ids = db.listDao().observeActiveWithCounts().first().map { it.list.id }
        assertEquals(listOf("pinned", "new", "old"), ids)
    }

    @Test
    fun archivedAndDeletedListsAreHidden() = runTest {
        db.listDao().upsert(list("visible"))
        db.listDao().upsert(list("archived", archived = true))
        db.listDao().upsert(list("deleted", deletedAt = 3L))

        val ids = db.listDao().observeActiveWithCounts().first().map { it.list.id }
        assertEquals(listOf("visible"), ids)
    }

    @Test
    fun openItemsComeBeforeBoughtThenManualOrder() = runTest {
        db.listDao().upsert(list("l"))
        db.itemDao().upsert(item("b-bought", "l", bought = true, sortOrder = 1.0))
        db.itemDao().upsert(item("a-second", "l", sortOrder = 2.0))
        db.itemDao().upsert(item("a-first", "l", sortOrder = 1.0))
        db.itemDao().upsert(item("gone", "l", deletedAt = 7L))

        val ids = db.itemDao().observeForList("l").first().map { it.id }
        assertEquals(listOf("a-first", "a-second", "b-bought"), ids)
    }

    @Test
    fun purgingAListCascadesToItsItems() = runTest {
        db.listDao().upsert(list("l"))
        db.itemDao().upsert(item("i", "l"))

        db.listDao().purge("l")

        assertNull(db.listDao().getById("l"))
        assertEquals(emptyList<ShoppingItemEntity>(), db.itemDao().observeForList("l").first())
    }

    @Test
    fun itemsCannotReferenceAMissingList() = runTest {
        try {
            db.itemDao().upsert(item("orphan", "no-such-list"))
            fail("Expected a foreign key violation")
        } catch (expected: SQLiteConstraintException) {
            // foreign keys are enforced
        }
    }
}
