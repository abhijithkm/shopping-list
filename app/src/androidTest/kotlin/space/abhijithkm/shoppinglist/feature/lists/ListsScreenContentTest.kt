package space.abhijithkm.shoppinglist.feature.lists

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.MemberRole
import space.abhijithkm.shoppinglist.ui.components.PickerColors
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

class ListsScreenContentTest { // stateless content tests, no Hilt needed
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun summary(
        id: String,
        total: Int = 0,
        bought: Int = 0,
        pinned: Boolean = false,
        archived: Boolean = false,
    ) = ListSummary(
        id = id,
        name = "List $id",
        color = null,
        isPinned = pinned,
        isArchived = archived,
        role = MemberRole.OWNER,
        totalCount = total,
        boughtCount = bought,
        updatedAt = 1L,
    )

    private fun show(state: ListsUiState, actions: ListsActions = ListsActions()) {
        composeRule.setContent { ShoppingListTheme { ListsScreenContent(state, actions) } }
        composeRule.waitForIdle() // v2 rule queues composition on a StandardTestDispatcher
    }

    private fun text(id: Int) = context.getString(id)

    @Test
    fun showsEmptyStateWhenThereAreNoLists() {
        show(ListsUiState(isLoading = false))
        composeRule.onNodeWithTag("lists_empty").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.lists_empty_body)).assertIsDisplayed()
    }

    @Test
    fun showsNoResultsMessageForASearchWithoutMatches() {
        show(ListsUiState(isLoading = false, query = "zzz"))
        composeRule.onNodeWithText(text(R.string.lists_no_results)).assertIsDisplayed()
    }

    @Test
    fun showsTheArchivedEmptyMessageAndNoCreateButton() {
        show(ListsUiState(isLoading = false, showArchived = true))
        composeRule.onNodeWithText(text(R.string.lists_archived_empty)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("list_card").assertCountEquals(0)
        composeRule.onAllNodesWithText(text(R.string.create_list)).assertCountEquals(0)
    }

    @Test
    fun showsOneCardPerListWithProgressText() {
        show(ListsUiState(listOf(summary("a", total = 6, bought = 2), summary("b")), isLoading = false))

        composeRule.onAllNodesWithTag("list_card").assertCountEquals(2)
        composeRule.onNodeWithText(context.getString(R.string.list_progress, 2, 6)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.list_no_items)).assertIsDisplayed()
    }

    @Test
    fun tappingACardOpensThatList() {
        var opened: String? = null
        show(
            ListsUiState(listOf(summary("a")), isLoading = false),
            ListsActions(onOpen = { opened = it }),
        )
        composeRule.onNodeWithTag("list_card").performClick()
        assertEquals("a", opened)
    }

    @Test
    fun createDialogReportsNameColourAndIcon() {
        var created: Triple<String, Int?, String?>? = null
        show(
            ListsUiState(isLoading = false),
            ListsActions(onCreate = { name, color, icon -> created = Triple(name, color, icon) }),
        )

        composeRule.onNodeWithText(text(R.string.create_list)).performClick()
        composeRule.onNodeWithTag("list_name_input").performTextInput("Weekend")
        composeRule.onNodeWithContentDescription(context.getString(R.string.color_option, 2)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.icon_option, "🛒")).performClick()
        composeRule.onNodeWithText(text(R.string.create)).performClick()

        assertEquals(Triple("Weekend", PickerColors[1], "🛒"), created)
    }

    @Test
    fun deletingFromTheCardMenuOffersUndo() {
        var deleted: String? = null
        var restored: String? = null
        show(
            ListsUiState(listOf(summary("a")), isLoading = false),
            ListsActions(onDelete = { deleted = it }, onRestore = { restored = it }),
        )

        composeRule.onNodeWithContentDescription(text(R.string.list_options)).performClick()
        composeRule.onNodeWithText(text(R.string.delete)).performClick()
        assertEquals("a", deleted)
        assertNull(restored)

        composeRule.onNodeWithText(text(R.string.list_deleted)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.undo)).performClick()
        composeRule.waitForIdle()
        assertEquals("a", restored)
    }

    @Test
    fun theCardMenuOffersUnpinForAPinnedListAndUnarchiveInTheArchive() {
        show(ListsUiState(listOf(summary("a", pinned = true, archived = true)), isLoading = false, showArchived = true))

        composeRule.onNodeWithContentDescription(text(R.string.list_options)).performClick()

        composeRule.onNodeWithText(text(R.string.unpin)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.unarchive)).assertIsDisplayed()
    }

    @Test
    fun searchReportsTheTypedQuery() {
        var query = ""
        show(ListsUiState(isLoading = false), ListsActions(onQueryChange = { query = it }))

        composeRule.onNodeWithContentDescription(text(R.string.search)).performClick()
        composeRule.onNodeWithTag("search_field").performTextInput("milk")

        assertEquals("milk", query)
    }

    @Test
    fun sortChoicesAreReportedFromTheOverflowMenu() {
        var sort: space.abhijithkm.shoppinglist.core.model.ListSort? = null
        show(ListsUiState(isLoading = false), ListsActions(onSortChange = { sort = it }))

        composeRule.onAllNodesWithContentDescription(text(R.string.more_options)).onFirst().performClick()
        composeRule.onNodeWithText(text(R.string.sort_name)).performClick()

        assertEquals(space.abhijithkm.shoppinglist.core.model.ListSort.NAME, sort)
    }
}
