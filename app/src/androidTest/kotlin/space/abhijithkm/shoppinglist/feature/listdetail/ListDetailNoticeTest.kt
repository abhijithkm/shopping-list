package space.abhijithkm.shoppinglist.feature.listdetail

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.MemberRole
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

/** A one-off message with an action (shopping mode switched on but notifications are off), shown as a snackbar. */
@RunWith(AndroidJUnit4::class)
class ListDetailNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val list = ListSummary(
        id = "l1",
        name = "Groceries",
        color = null,
        isPinned = false,
        role = MemberRole.OWNER,
        totalCount = 0,
        boughtCount = 0,
        updatedAt = 1L,
    )

    private fun state() = buildListDetailState(list, emptyList(), emptyList(), emptyList(), AppSettings(), ItemFilter())
        .copy(isLoading = false)

    @Test
    fun aNoticeShowsItsMessageAndItsActionRuns() {
        var acted = false
        composeRule.setContent {
            ShoppingListTheme {
                ListDetailContent(
                    state = state(),
                    actions = ListDetailActions(),
                    notice = ListDetailNotice(id = 1L, message = "Notifications are off for shopping mode", actionLabel = "Open settings", onAction = { acted = true }),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Notifications are off for shopping mode").assertExists()
        composeRule.onNodeWithText("Open settings").performClick()

        assertTrue(acted)
    }

    @Test
    fun withoutANoticeNothingIsShown() {
        composeRule.setContent { ShoppingListTheme { ListDetailContent(state = state(), actions = ListDetailActions()) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Open settings").assertDoesNotExist()
    }
}
