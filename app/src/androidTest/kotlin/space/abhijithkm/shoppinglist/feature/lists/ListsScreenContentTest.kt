package space.abhijithkm.shoppinglist.feature.lists

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.MemberRole
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

class ListsScreenContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun summary(id: String, total: Int, bought: Int) = ListSummary(
        id = id,
        name = "List $id",
        color = null,
        isPinned = false,
        role = MemberRole.OWNER,
        totalCount = total,
        boughtCount = bought,
        updatedAt = 1L,
    )

    @Test
    fun showsEmptyStateWhenThereAreNoLists() {
        composeRule.setContent {
            ShoppingListTheme { ListsScreenContent(ListsUiState(isLoading = false), onCreateList = {}) }
        }
        composeRule.onNodeWithTag("lists_empty").assertIsDisplayed()
    }

    @Test
    fun showsOneCardPerListWithProgressText() {
        val state = ListsUiState(listOf(summary("a", total = 6, bought = 2), summary("b", 0, 0)), isLoading = false)
        composeRule.setContent { ShoppingListTheme { ListsScreenContent(state, onCreateList = {}) } }

        composeRule.onAllNodesWithTag("list_card").assertCountEquals(2)
        composeRule.onNodeWithText(context.getString(R.string.list_progress, 2, 6)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.list_no_items)).assertIsDisplayed()
    }

    @Test
    fun createDialogReportsTheTypedName() {
        var created: String? = null
        composeRule.setContent {
            ShoppingListTheme {
                ListsScreenContent(ListsUiState(isLoading = false), onCreateList = { created = it })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.create_list)).performClick()
        composeRule.onNodeWithTag("list_name_input").performTextInput("Weekend")
        composeRule.onNodeWithText(context.getString(R.string.create)).performClick()

        assertEquals("Weekend", created)
    }
}
