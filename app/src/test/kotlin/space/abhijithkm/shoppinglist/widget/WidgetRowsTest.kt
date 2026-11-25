package space.abhijithkm.shoppinglist.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity

class WidgetRowsTest {
    private fun list(archived: Boolean = false, deletedAt: Long? = null) =
        ShoppingListEntity(id = "l1", name = "Groceries", isArchived = archived, createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt)

    private fun item(
        id: String,
        name: String = "Item $id",
        bought: Boolean = false,
        sortOrder: Double = 0.0,
        quantity: Double? = null,
        unit: String? = null,
        deletedAt: Long? = null,
    ) = ShoppingItemEntity(
        id = id,
        listId = "l1",
        name = name,
        quantity = quantity,
        unit = unit,
        isBought = bought,
        sortOrder = sortOrder,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = deletedAt,
    )

    private fun content(model: WidgetModel) = model as WidgetModel.Content

    @Test
    fun unavailableForAMissingList() {
        assertEquals(WidgetModel.Unavailable, buildWidgetModel(null, listOf(item("a"))))
    }

    @Test
    fun unavailableForADeletedList() {
        assertEquals(WidgetModel.Unavailable, buildWidgetModel(list(deletedAt = 5L), listOf(item("a"))))
    }

    @Test
    fun unavailableForAnArchivedList() {
        assertEquals(WidgetModel.Unavailable, buildWidgetModel(list(archived = true), listOf(item("a"))))
    }

    @Test
    fun openItemsComeBeforeBoughtOnesEachInManualOrder() {
        val model = content(
            buildWidgetModel(
                list(),
                listOf(item("a", bought = true, sortOrder = 1.0), item("b", sortOrder = 2.0), item("c", sortOrder = 0.0)),
            ),
        )
        assertEquals(listOf("c", "b", "a"), model.rows.map { it.itemId })
        assertEquals(listOf(false, false, true), model.rows.map { it.bought })
    }

    @Test
    fun theLabelUsesQuantityAndUnitWhenTheyExist() {
        val model = content(
            buildWidgetModel(
                list(),
                listOf(
                    item("a", name = "milk", quantity = 2.0, unit = "kg", sortOrder = 0.0),
                    item("b", name = "mangoes", quantity = 3.0, sortOrder = 1.0),
                    item("c", name = "bread", sortOrder = 2.0),
                ),
            ),
        )
        assertEquals(listOf("2 kg milk", "3 mangoes", "bread"), model.rows.map { it.label })
    }

    @Test
    fun deletedItemsAreIgnored() {
        val model = content(buildWidgetModel(list(), listOf(item("a"), item("b", deletedAt = 9L))))
        assertEquals(listOf("a"), model.rows.map { it.itemId })
        assertEquals(1, model.totalCount)
    }

    @Test
    fun rowsAreCappedButTheCountsAreNot() {
        val items = (1..5).map { item("i$it", sortOrder = it.toDouble()) }
        val model = content(buildWidgetModel(list(), items, maxRows = 2))
        assertEquals(2, model.rows.size)
        assertEquals(5, model.openCount)
        assertEquals(5, model.totalCount)
    }

    @Test
    fun aFullyTickedListIsStillContentWithZeroOpen() {
        val model = content(buildWidgetModel(list(), listOf(item("a", bought = true))))
        assertEquals(0, model.openCount)
        assertEquals(1, model.totalCount)
        assertEquals("Groceries", model.listName)
        assertTrue(model.rows.single().bought)
    }

    @Test
    fun anEmptyListIsContentWithNoRows() {
        val model = content(buildWidgetModel(list(), emptyList()))
        assertEquals(0, model.totalCount)
        assertTrue(model.rows.isEmpty())
    }
}
