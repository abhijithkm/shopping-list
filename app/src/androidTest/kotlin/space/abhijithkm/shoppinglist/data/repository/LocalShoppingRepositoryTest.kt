package space.abhijithkm.shoppinglist.data.repository

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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.data.local.AppDatabase

private class FakeClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis() = now
}

private class SequentialIds : IdGenerator {
    private var n = 0
    override fun newId() = "id-${++n}"
}

@RunWith(AndroidJUnit4::class)
class LocalShoppingRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LocalShoppingRepository
    private val clock = FakeClock()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = LocalShoppingRepository(db, clock, SequentialIds())
    }

    @After
    fun close() = db.close()

    private suspend fun names(listId: String) = repo.observeItems(listId).first().map { it.name }

    private suspend fun update(
        itemId: String,
        name: String = "x",
        quantity: Double? = null,
        unit: String? = null,
        categoryKey: String? = null,
        notes: String? = null,
        tagIds: Set<String> = emptySet(),
    ) = repo.updateItem(ItemUpdate(itemId, name, quantity, unit, categoryKey, notes, tagIds))

    // ---------------------------------------------------------------- items

    @Test
    fun quickAddParsesQuantityAndAppendsInOrder() = runTest {
        val list = repo.createList("Monthly")
        repo.addItem(list, "2 kg milk")
        repo.addItem(list, "eggs")

        val items = repo.observeItems(list).first()
        assertEquals(listOf("milk", "eggs"), items.map { it.name })
        assertEquals(2.0, items[0].quantity!!, 0.0)
        assertEquals("kg", items[0].unit)
        assertNull(items[1].quantity)
        assertTrue(items[0].sortOrder < items[1].sortOrder)
    }

    @Test
    fun blankQuickAddIsIgnored() = runTest {
        val list = repo.createList("L")
        assertNull(repo.addItem(list, "   "))
        assertTrue(repo.observeItems(list).first().isEmpty())
    }

    @Test
    fun boughtItemsKeepTheirPositionWhenUnbought() = runTest {
        val list = repo.createList("L")
        repo.addItem(list, "a")
        val b = repo.addItem(list, "b")!!
        repo.addItem(list, "c")
        val originalOrder = repo.observeItems(list).first().first { it.id == b }.sortOrder

        clock.now = 5_000L
        repo.setItemBought(b, true)
        val bought = repo.observeItems(list).first().first { it.id == b }
        assertTrue(bought.isBought)
        assertEquals(5_000L, bought.boughtAt)

        repo.setItemBought(b, false)
        val back = repo.observeItems(list).first().first { it.id == b }
        assertFalse(back.isBought)
        assertNull(back.boughtAt)
        assertEquals(originalOrder, back.sortOrder, 0.0)
        assertEquals(listOf("a", "b", "c"), names(list))
    }

    @Test
    fun deletedItemsDisappearAndCanBeRestored() = runTest {
        val list = repo.createList("L")
        val a = repo.addItem(list, "a")!!
        repo.deleteItem(a)
        assertTrue(repo.observeItems(list).first().isEmpty())
        repo.restoreItem(a)
        assertEquals(listOf("a"), names(list))
    }

    @Test
    fun clearCompletedReturnsIdsAndCanBeUndone() = runTest {
        val list = repo.createList("L")
        val a = repo.addItem(list, "a")!!
        repo.addItem(list, "b")
        val c = repo.addItem(list, "c")!!
        repo.setItemBought(a, true)
        repo.setItemBought(c, true)

        val cleared = repo.clearCompleted(list)

        assertEquals(setOf(a, c), cleared.toSet())
        assertEquals(listOf("b"), names(list))
        repo.restoreItems(cleared)
        assertEquals(listOf("a", "b", "c"), names(list))
        assertTrue(repo.clearCompleted(repo.createList("empty")).isEmpty())
    }

    @Test
    fun moveItemPlacesItBetweenItsNeighbours() = runTest {
        val list = repo.createList("L")
        val a = repo.addItem(list, "a")!!
        val b = repo.addItem(list, "b")!!
        val c = repo.addItem(list, "c")!!

        repo.moveItem(c, aboveId = a, belowId = b)
        assertEquals(listOf("a", "c", "b"), names(list))

        repo.moveItem(a, aboveId = b, belowId = null)
        assertEquals(listOf("c", "b", "a"), names(list))

        repo.moveItem(a, aboveId = null, belowId = c)
        assertEquals(listOf("a", "c", "b"), names(list))
    }

    @Test
    fun moveItemRenumbersWhenTheGapIsExhausted() = runTest {
        val list = repo.createList("L")
        val a = repo.addItem(list, "a")!!
        val b = repo.addItem(list, "b")!!
        val c = repo.addItem(list, "c")!!
        db.itemDao().setSortOrder(a, 1.0, 1L)
        db.itemDao().setSortOrder(b, 1.0000001, 1L)

        repo.moveItem(c, aboveId = a, belowId = b)

        assertEquals(listOf("a", "c", "b"), names(list))
        val orders = repo.observeItems(list).first().map { it.sortOrder }
        assertTrue("orders must be strictly increasing: $orders", orders.zipWithNext().all { (x, y) -> x < y })
    }

    @Test
    fun updateItemChangesFieldsTagsAndCategory() = runTest {
        val list = repo.createList("L")
        val item = repo.addItem(list, "milk")!!
        val urgent = repo.createTag("Urgent")

        update(
            item,
            name = "Oat milk",
            quantity = 3.0,
            unit = " packets ",
            categoryKey = "dairy",
            notes = "  organic  ",
            tagIds = setOf(urgent),
        )

        val saved = repo.observeItems(list).first().single()
        assertEquals("Oat milk", saved.name)
        assertEquals(3.0, saved.quantity!!, 0.0)
        assertEquals("packets", saved.unit)
        assertEquals("dairy", saved.categoryKey)
        assertEquals("organic", saved.notes)
        assertEquals(listOf("Urgent"), saved.tags.map { it.name })
    }

    @Test
    fun updateItemDropsInvalidOptionalValues() = runTest {
        val list = repo.createList("L")
        val item = repo.addItem(list, "milk")!!

        update(item, quantity = 0.0, unit = "  ", categoryKey = "no-such-category", notes = "   ", tagIds = setOf("ghost"))

        val saved = repo.observeItems(list).first().single()
        assertNull(saved.quantity)
        assertNull(saved.unit)
        assertNull(saved.categoryKey)
        assertNull(saved.notes)
        assertTrue(saved.tags.isEmpty())
    }

    @Test
    fun updateItemRejectsABlankName() = runTest {
        val list = repo.createList("L")
        val item = repo.addItem(list, "milk")!!
        try {
            update(item, name = "   ")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals(listOf("milk"), names(list))
        }
    }

    // ------------------------------------------------------ categories, tags

    @Test
    fun tagsAreUniqueIgnoringCaseAndDeletingRemovesTheirLinks() = runTest {
        val list = repo.createList("L")
        val item = repo.addItem(list, "milk")!!
        val first = repo.createTag("Weekend")
        assertEquals(first, repo.createTag("weekend"))
        update(item, name = "milk", tagIds = setOf(first))
        assertEquals(1, repo.observeItems(list).first().single().tags.size)

        repo.deleteTag(first)

        assertTrue(repo.observeTags().first().isEmpty())
        assertTrue(repo.observeItems(list).first().single().tags.isEmpty())
    }

    @Test
    fun renamingATagToAnExistingNameIsRefused() = runTest {
        val a = repo.createTag("Urgent")
        val b = repo.createTag("Monthly")
        assertFalse(repo.renameTag(b, "urgent"))
        assertTrue(repo.renameTag(b, "Every month"))
        assertEquals(setOf("Urgent", "Every month"), repo.observeTags().first().map { it.name }.toSet())
        assertNotNull(a)
    }

    @Test
    fun aBuiltInCategoryCanBeRenamedRecolouredAndReset() = runTest {
        val before = repo.observeCategories().first().first { it.key == "dairy" }
        assertNull(before.name)
        assertFalse(before.isCustomized)

        repo.updateCategory("dairy", name = "Milk & co", color = 0xFF112233.toInt())
        val changed = repo.observeCategories().first().first { it.key == "dairy" }
        assertEquals("Milk & co", changed.name)
        assertEquals(0xFF112233.toInt(), changed.color)
        assertTrue(changed.isCustomized)

        repo.deleteCategory("dairy")
        val reset = repo.observeCategories().first().first { it.key == "dairy" }
        assertNull(reset.name)
        assertFalse(reset.isCustomized)
        assertEquals(before.color, reset.color)
    }

    @Test
    fun deletingACustomCategoryUncategorisesItsItems() = runTest {
        val list = repo.createList("L")
        val item = repo.addItem(list, "kibble")!!
        val pets = repo.createCategory("Pets", color = 0xFF00AA00.toInt())
        update(item, name = "kibble", categoryKey = pets)
        assertEquals(pets, repo.observeItems(list).first().single().categoryKey)
        assertTrue(repo.observeCategories().first().any { it.key == pets && it.name == "Pets" })

        repo.deleteCategory(pets)

        assertNull(repo.observeItems(list).first().single().categoryKey)
        assertFalse(repo.observeCategories().first().any { it.key == pets })
    }

    // ---------------------------------------------------------------- lists

    @Test
    fun listLifecycleRenamePinArchiveDeleteRestore() = runTest {
        val id = repo.createList("Groceries", color = 0xFF123456.toInt(), icon = "🛒")
        repo.addItem(id, "a")
        val bought = repo.addItem(id, "b")!!
        repo.setItemBought(bought, true)

        val created = repo.observeLists().first().single()
        assertEquals("Groceries", created.name)
        assertEquals("🛒", created.icon)
        assertEquals(2, created.totalCount)
        assertEquals(1, created.boughtCount)
        assertEquals(1, created.remainingCount)

        repo.renameList(id, "  Monthly  ")
        repo.setListPinned(id, true)
        assertEquals("Monthly", repo.observeList(id).first()!!.name)
        assertTrue(repo.observeList(id).first()!!.isPinned)

        repo.setListArchived(id, true)
        assertTrue(repo.observeLists(archived = false).first().isEmpty())
        assertEquals(listOf("Monthly"), repo.observeLists(archived = true).first().map { it.name })
        repo.setListArchived(id, false)

        repo.deleteList(id)
        assertTrue(repo.observeLists().first().isEmpty())
        assertNull(repo.observeList(id).first())
        repo.restoreList(id)
        assertEquals("Monthly", repo.observeList(id).first()!!.name)
    }

    @Test
    fun aListsLastUpdatedMovesWhenItsItemsChange() = runTest {
        val id = repo.createList("L")
        val created = repo.observeList(id).first()!!.updatedAt
        clock.now = 9_000L
        repo.addItem(id, "a")
        assertEquals(9_000L, repo.observeList(id).first()!!.updatedAt)
        assertTrue(repo.observeList(id).first()!!.updatedAt > created)
    }

    @Test
    fun purgingRemovesOnlyOldTombstones() = runTest {
        clock.now = 1_000L
        val old = repo.createList("old")
        repo.deleteList(old)
        clock.now = 5_000L
        val recent = repo.createList("recent")
        repo.deleteList(recent)
        val kept = repo.createList("kept")

        repo.purgeExpiredTombstones(cutoffMillis = 3_000L)

        assertNull(db.listDao().getById(old))
        assertNotNull(db.listDao().getById(recent))
        assertNotNull(db.listDao().getById(kept))
    }

    @Test
    fun blankListNamesAreRejected() = runTest {
        try {
            repo.createList("  ")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(repo.observeLists().first().isEmpty())
        }
    }
}
