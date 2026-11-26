package space.abhijithkm.shoppinglist.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity

class ShoppingModeContentTest {
    private fun list(archived: Boolean = false, deletedAt: Long? = null) =
        ShoppingListEntity(id = "l1", name = "Groceries", isArchived = archived, createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt)

    private fun item(
        id: String,
        bought: Boolean = false,
        sortOrder: Double = 0.0,
        deletedAt: Long? = null,
    ) = ShoppingItemEntity(id = id, listId = "l1", name = "Item $id", isBought = bought, sortOrder = sortOrder, createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt)

    @Test
    fun nullForAMissingList() {
        assertNull(shoppingModeContent(null, listOf(item("a"))))
    }

    @Test
    fun nullForADeletedList() {
        assertNull(shoppingModeContent(list(deletedAt = 5L), listOf(item("a"))))
    }

    @Test
    fun nullForAnArchivedList() {
        assertNull(shoppingModeContent(list(archived = true), listOf(item("a"))))
    }

    @Test
    fun nullWhenEveryItemIsBought() {
        assertNull(shoppingModeContent(list(), listOf(item("a", bought = true), item("b", bought = true))))
    }

    @Test
    fun nullForAnEmptyList() {
        assertNull(shoppingModeContent(list(), emptyList()))
    }

    @Test
    fun countsIgnoreDeletedItems() {
        val content = shoppingModeContent(
            list(),
            listOf(item("a"), item("b", deletedAt = 9L), item("c", bought = true), item("d", bought = true, deletedAt = 9L)),
        )
        assertNotNull(content)
        assertEquals(1, content!!.openCount)
        assertEquals(2, content.totalCount)
    }

    @Test
    fun totalIncludesBoughtItems() {
        val content = shoppingModeContent(list(), listOf(item("a"), item("b"), item("c", bought = true)))!!
        assertEquals(2, content.openCount)
        assertEquals(3, content.totalCount)
        assertEquals("Groceries", content.listName)
        assertEquals("l1", content.listId)
    }

    @Test
    fun showsAtMostFiveOpenItemNamesInSortOrder() {
        val items = (1..8).map { item("i$it", sortOrder = (9 - it).toDouble()) } + item("done", bought = true, sortOrder = 0.0)
        val content = shoppingModeContent(list(), items)!!
        assertEquals(listOf("Item i8", "Item i7", "Item i6", "Item i5", "Item i4"), content.nextItems)
        assertEquals(8, content.openCount)
    }

    @Test
    fun maxItemsParameterIsHonoured() {
        val content = shoppingModeContent(list(), listOf(item("a", sortOrder = 1.0), item("b", sortOrder = 2.0)), maxItems = 1)!!
        assertEquals(listOf("Item a"), content.nextItems)
    }
}
