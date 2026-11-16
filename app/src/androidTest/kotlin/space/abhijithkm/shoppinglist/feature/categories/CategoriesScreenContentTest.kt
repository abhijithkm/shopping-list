package space.abhijithkm.shoppinglist.feature.categories

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.DefaultCategory
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.ui.components.PickerColors
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

class CategoriesScreenContentTest { // stateless content tests, no Hilt needed
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val builtIns = DefaultCategory.entries.map { Category(it.key, null, it.colorArgb.toInt(), it, false) }
    private val pets = Category("c-pets", "Pets", 0xFF00AA00.toInt(), null, false)
    private val urgent = Tag("t1", "Urgent")

    private fun show(state: CategoriesUiState, actions: CategoriesActions = CategoriesActions()) {
        composeRule.setContent { ShoppingListTheme { CategoriesScreenContent(state, actions) } }
        composeRule.waitForIdle() // v2 rule queues composition on a StandardTestDispatcher
    }

    private fun text(id: Int) = context.getString(id)

    private fun state(
        categories: List<Category> = builtIns + pets,
        tags: List<Tag> = listOf(urgent),
    ) = CategoriesUiState(categories, tags, isLoading = false)

    @Test
    fun listsTheBuiltInsCustomCategoriesAndTags() {
        show(state())
        composeRule.onNodeWithText("Vegetables").assertIsDisplayed()
        composeRule.onNodeWithText("Pets").assertIsDisplayed()
        composeRule.onNodeWithText("#Urgent").assertIsDisplayed()
        composeRule.onAllNodesWithTag("category_row").assertCountEquals(12)
    }

    @Test
    fun showsAHintWhenThereAreNoTags() {
        show(state(tags = emptyList()))
        composeRule.onNodeWithTag("tags_empty").assertIsDisplayed()
    }

    @Test
    fun renamingAndRecolouringABuiltInReportsTheChanges() {
        var update: Triple<String, String?, Int?>? = null
        show(state(), CategoriesActions(onUpdateCategory = { key, name, color -> update = Triple(key, name, color) }))

        composeRule.onNodeWithText("Dairy").performClick()
        composeRule.onNodeWithTag("category_name_input").performTextClearance()
        composeRule.onNodeWithTag("category_name_input").performTextInput("Milk and co")
        composeRule.onNodeWithContentDescription(context.getString(R.string.color_option, 3)).performClick()
        composeRule.onNodeWithTag("category_save").performClick()

        assertEquals(Triple("dairy", "Milk and co", PickerColors[2]), update)
    }

    @Test
    fun savingWithoutChangesReportsNothingChanged() {
        var update: Triple<String, String?, Int?>? = null
        show(state(), CategoriesActions(onUpdateCategory = { key, name, color -> update = Triple(key, name, color) }))

        composeRule.onNodeWithText("Dairy").performClick()
        composeRule.onNodeWithTag("category_save").performClick()

        assertEquals(Triple<String, String?, Int?>("dairy", null, null), update)
    }

    @Test
    fun anUntouchedBuiltInHasNothingToResetButACustomOneCanBeDeleted() {
        var deleted: String? = null
        show(state(), CategoriesActions(onDeleteCategory = { deleted = it }))

        composeRule.onNodeWithText("Dairy").performClick()
        composeRule.onAllNodesWithTag("category_delete_or_reset").assertCountEquals(0)
        composeRule.onNodeWithText(text(R.string.cancel)).performClick()

        composeRule.onNodeWithText("Pets").performClick()
        composeRule.onNodeWithText(text(R.string.category_delete_note)).assertIsDisplayed()
        composeRule.onNodeWithTag("category_delete_or_reset").performClick()

        assertEquals("c-pets", deleted)
    }

    @Test
    fun aCustomisedBuiltInOffersResetToDefault() {
        var reset: String? = null
        val customised = builtIns.map { if (it.key == "dairy") it.copy(name = "Milk", isCustomized = true) else it }
        show(state(categories = customised), CategoriesActions(onDeleteCategory = { reset = it }))

        composeRule.onNodeWithText("Milk").performClick()
        composeRule.onNodeWithText(text(R.string.category_reset)).performClick()

        assertEquals("dairy", reset)
    }

    @Test
    fun creatingACategoryReportsNameAndColour() {
        var created: Pair<String, Int?>? = null
        show(state(), CategoriesActions(onCreateCategory = { name, color -> created = name to color }))

        composeRule.onNodeWithText(text(R.string.category_new)).performClick()
        composeRule.onNodeWithTag("category_name_input").performTextInput("Hobbies")
        composeRule.onNodeWithContentDescription(context.getString(R.string.color_option, 2)).performClick()
        composeRule.onNodeWithTag("category_save").performClick()

        assertEquals("Hobbies" to PickerColors[1], created)
    }

    @Test
    fun theSaveButtonIsDisabledForABlankName() {
        show(state())
        composeRule.onNodeWithText(text(R.string.category_new)).performClick()
        composeRule.onNodeWithTag("category_save").assertIsNotEnabled()
    }

    @Test
    fun renamingATagToADuplicateShowsAnError() {
        show(state(), CategoriesActions(onRenameTag = { _, _, onResult -> onResult(false) }))

        composeRule.onNodeWithText("#Urgent").performClick()
        composeRule.onNodeWithTag("tag_name_input").performTextInput("2")
        composeRule.onNodeWithTag("tag_save").performClick()

        composeRule.onNodeWithText(text(R.string.tag_exists)).assertIsDisplayed()
    }

    @Test
    fun aSuccessfulTagRenameClosesTheDialog() {
        var renamed: Pair<String, String>? = null
        show(
            state(),
            CategoriesActions(onRenameTag = { id, name, onResult ->
                renamed = id to name
                onResult(true)
            }),
        )

        composeRule.onNodeWithText("#Urgent").performClick()
        composeRule.onNodeWithTag("tag_name_input").performTextClearance()
        composeRule.onNodeWithTag("tag_name_input").performTextInput("Rush")
        composeRule.onNodeWithTag("tag_save").performClick()

        assertEquals("t1" to "Rush", renamed)
        composeRule.onAllNodesWithText(text(R.string.tag_edit)).assertCountEquals(0)
    }

    @Test
    fun deletingATagIsReported() {
        var deleted: String? = null
        show(state(), CategoriesActions(onDeleteTag = { deleted = it }))

        composeRule.onNodeWithText("#Urgent").performClick()
        composeRule.onNodeWithTag("tag_delete").performClick()

        assertEquals("t1", deleted)
    }
}
