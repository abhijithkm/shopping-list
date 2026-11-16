package space.abhijithkm.shoppinglist.feature.listdetail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.DefaultCategory
import space.abhijithkm.shoppinglist.core.model.Item
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.MemberRole
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

class ListDetailContentTest { // stateless content tests, no Hilt needed
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dairy = Category("dairy", null, 0xFF42A5F5.toInt(), DefaultCategory.DAIRY, false)
    private val urgent = Tag("t1", "Urgent")
    private val list = ListSummary(
        id = "l1",
        name = "Monthly Groceries",
        color = null,
        icon = "🛒",
        isPinned = false,
        role = MemberRole.OWNER,
        totalCount = 3,
        boughtCount = 1,
        updatedAt = 1L,
    )

    private fun item(
        id: String,
        name: String,
        bought: Boolean = false,
        category: String? = null,
        quantity: Double? = null,
        unit: String? = null,
        order: Double = 0.0,
        boughtAt: Long? = null,
        tags: List<Tag> = emptyList(),
    ) = Item(id, "l1", name, quantity, unit, category, null, bought, boughtAt, order, tags)

    private val sample = listOf(
        item("a", "Milk", category = "dairy", quantity = 2.0, unit = "litres", order = 1.0),
        item("b", "Bread", order = 2.0),
        item("c", "Rice", bought = true, boughtAt = 5L, order = 3.0),
    )

    private fun stateOf(
        items: List<Item> = sample,
        settings: AppSettings = AppSettings(),
        filter: ItemFilter = ItemFilter(),
    ) = buildListDetailState(list, items, listOf(dairy), listOf(urgent), settings, filter).copy(isLoading = false)

    private fun show(state: ListDetailUiState, actions: ListDetailActions = ListDetailActions()) {
        composeRule.setContent { ShoppingListTheme { ListDetailContent(state, actions) } }
        composeRule.waitForIdle() // v2 rule queues composition on a StandardTestDispatcher
    }

    private fun text(id: Int) = context.getString(id)

    @Test
    fun anEmptyListInvitesTheFirstItem() {
        show(stateOf(items = emptyList()))
        composeRule.onNodeWithTag("items_empty").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.items_empty_body)).assertIsDisplayed()
    }

    @Test
    fun showsOpenItemsThenTheCompletedSectionWithProgress() {
        show(stateOf())

        composeRule.onNodeWithText("Milk").assertIsDisplayed()
        composeRule.onNodeWithText("Bread").assertIsDisplayed()
        composeRule.onNodeWithText("Rice").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.completed_section, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.progress_summary, 1, 3)).assertIsDisplayed()
        composeRule.onNodeWithText("2 litres · Dairy").assertIsDisplayed()
        composeRule.onAllNodesWithTag("item_row").assertCountEquals(3)
    }

    @Test
    fun completedItemsCanBeHiddenWithTheSetting() {
        show(stateOf(settings = AppSettings(showCompleted = false)))
        composeRule.onAllNodesWithText("Rice").assertCountEquals(0)
        composeRule.onAllNodesWithText(context.getString(R.string.completed_section, 1)).assertCountEquals(0)
    }

    @Test
    fun checkingAnItemReportsTheChange() {
        var toggled: Pair<String, Boolean>? = null
        show(stateOf(), ListDetailActions(onSetBought = { id, bought -> toggled = id to bought }))

        composeRule.onNodeWithContentDescription(context.getString(R.string.mark_bought, "Milk")).performClick()

        assertEquals("a" to true, toggled)
    }

    @Test
    fun uncheckingACompletedItemReportsTheChange() {
        var toggled: Pair<String, Boolean>? = null
        show(stateOf(), ListDetailActions(onSetBought = { id, bought -> toggled = id to bought }))

        composeRule.onNodeWithContentDescription(context.getString(R.string.mark_not_bought, "Rice")).performClick()

        assertEquals("c" to false, toggled)
    }

    @Test
    fun theAddButtonIsDisabledUntilSomethingIsTyped() {
        show(stateOf())
        composeRule.onNodeWithTag("quick_add_button").assertIsNotEnabled()
    }

    @Test
    fun quickAddSubmitsTheTextAndClearsTheField() {
        var added: String? = null
        show(stateOf(), ListDetailActions(onAddItem = { added = it }))

        composeRule.onNodeWithTag("quick_add_field").performTextInput("2 kg tomatoes")
        composeRule.onNodeWithTag("quick_add_button").performClick()

        assertEquals("2 kg tomatoes", added)
        composeRule.onNodeWithTag("quick_add_button").assertIsNotEnabled()
    }

    @Test
    fun tappingAnItemOpensTheEditSheetAndSaveReportsTheUpdate() {
        var saved: ItemUpdate? = null
        show(stateOf(), ListDetailActions(onUpdateItem = { saved = it }))

        composeRule.onNodeWithText("Bread").performClick()
        composeRule.onNodeWithTag("edit_name").performTextClearance()
        composeRule.onNodeWithTag("edit_name").performTextInput("Brown bread")
        composeRule.onNodeWithTag("edit_quantity").performTextInput("1,5")
        composeRule.onNodeWithTag("edit_save").assertIsDisplayed()
        composeRule.onNodeWithTag("edit_save").assertIsEnabled()
        composeRule.onNodeWithTag("edit_save").performClick()

        val update = saved!!
        assertEquals("b", update.id)
        assertEquals("Brown bread", update.name)
        assertEquals(1.5, update.quantity!!, 0.0)
        assertNull(update.categoryKey)
    }

    @Test
    fun anInvalidQuantityBlocksSaving() {
        show(stateOf())
        composeRule.onNodeWithText("Bread").performClick()
        composeRule.onNodeWithTag("edit_quantity").performTextInput("abc")
        composeRule.onNodeWithText(text(R.string.quantity_invalid)).assertIsDisplayed()
        composeRule.onNodeWithTag("edit_save").assertIsNotEnabled()
    }

    @Test
    fun deletingFromTheEditSheetOffersUndo() {
        var deleted: String? = null
        var restored: String? = null
        show(stateOf(), ListDetailActions(onDeleteItem = { deleted = it }, onRestoreItem = { restored = it }))

        composeRule.onNodeWithText("Bread").performClick()
        composeRule.onNodeWithText(text(R.string.delete)).performClick()
        assertEquals("b", deleted)

        composeRule.onNodeWithText(text(R.string.item_deleted)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.undo)).performClick()
        composeRule.waitForIdle()
        assertEquals("b", restored)
    }

    @Test
    fun groupingShowsCategoryHeaders() {
        show(stateOf(settings = AppSettings(groupByCategory = true)))
        composeRule.onNodeWithText("Dairy").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.uncategorised)).assertIsDisplayed()
    }

    @Test
    fun clearingCompletedItemsOffersUndo() {
        var restored: List<String>? = null
        show(
            stateOf(),
            ListDetailActions(
                onClearCompleted = { callback -> callback(listOf("c", "d")) },
                onRestoreItems = { restored = it },
            ),
        )

        composeRule.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        composeRule.onNodeWithText(text(R.string.clear_completed)).performClick()

        composeRule.onNodeWithText(context.resources.getQuantityString(R.plurals.completed_cleared, 2, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.undo)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("c", "d"), restored)
    }

    @Test
    fun clearCompletedIsDisabledWhenNothingIsBought() {
        show(stateOf(items = listOf(item("a", "Milk"))))
        composeRule.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        composeRule.onNodeWithText(text(R.string.clear_completed)).assertIsNotEnabled()
    }

    @Test
    fun allItemsBoughtShowsTheAllDoneMessage() {
        show(stateOf(items = listOf(item("a", "Milk", bought = true, boughtAt = 1L))))
        composeRule.onNodeWithTag("all_done").assertIsDisplayed()
    }

    @Test
    fun theSearchPanelReportsTheQueryAndOffersCategoryChips() {
        var query = ""
        var categoryToggled: String? = null
        show(
            stateOf(),
            ListDetailActions(onQueryChange = { query = it }, onToggleCategoryFilter = { categoryToggled = it }),
        )

        composeRule.onNodeWithContentDescription(text(R.string.search_items)).performClick()
        composeRule.onNodeWithTag("item_search").performTextInput("br")
        assertEquals("br", query)

        composeRule.onAllNodesWithText("Dairy").onFirst().performClick()
        assertEquals("dairy", categoryToggled)
    }

    @Test
    fun anActiveFilterWithoutMatchesSaysSo() {
        show(stateOf(filter = ItemFilter(query = "zzz")))
        composeRule.onNodeWithText(text(R.string.filter_no_match)).assertIsDisplayed()
    }
}
