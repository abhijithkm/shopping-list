package space.abhijithkm.shoppinglist.feature.categories

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.DefaultCategory
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val dairy = Category("dairy", null, 1, DefaultCategory.DAIRY, false)
    private val pets = Category("c-pets", "Pets", 2, null, false)
    private val urgent = Tag("t1", "Urgent")

    private fun repository(renameTagResult: Boolean = true) = mockk<LocalShoppingRepository> {
        every { observeCategories() } returns flowOf(listOf(dairy, pets))
        every { observeTags() } returns flowOf(listOf(urgent))
        coEvery { createCategory(any(), any()) } returns "new"
        coEvery { updateCategory(any(), any(), any()) } returns Unit
        coEvery { deleteCategory(any()) } returns Unit
        coEvery { renameTag(any(), any()) } returns renameTagResult
        coEvery { deleteTag(any()) } returns Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state combines categories and tags and stops loading`() = runTest {
        val state = CategoriesViewModel(repository()).uiState.first { !it.isLoading }
        assertEquals(listOf("dairy", "c-pets"), state.categories.map { it.key })
        assertEquals(listOf("Urgent"), state.tags.map { it.name })
    }

    @Test
    fun `createCategory trims the name and ignores blanks`() = runTest {
        val repository = repository()
        val viewModel = CategoriesViewModel(repository)

        viewModel.createCategory("  Pets  ", 5)
        viewModel.createCategory("   ", 5)

        coVerify(exactly = 1) { repository.createCategory("Pets", 5) }
        coVerify(exactly = 0) { repository.createCategory("", any()) }
    }

    @Test
    fun `updateCategory passes unchanged attributes as null`() = runTest {
        val repository = repository()
        val viewModel = CategoriesViewModel(repository)

        viewModel.updateCategory("dairy", name = null, color = 9)
        viewModel.updateCategory("dairy", name = "  Milk & co ", color = null)
        viewModel.updateCategory("dairy", name = "   ", color = 1)

        coVerify(exactly = 1) { repository.updateCategory("dairy", null, 9) }
        coVerify(exactly = 1) { repository.updateCategory("dairy", "Milk & co", null) }
        coVerify(exactly = 0) { repository.updateCategory("dairy", "", any()) }
    }

    @Test
    fun `deleting a category or tag is delegated`() = runTest {
        val repository = repository()
        val viewModel = CategoriesViewModel(repository)

        viewModel.deleteCategory("c-pets")
        viewModel.deleteTag("t1")

        coVerify(exactly = 1) { repository.deleteCategory("c-pets") }
        coVerify(exactly = 1) { repository.deleteTag("t1") }
    }

    @Test
    fun `renameTag reports the repository result`() = runTest {
        var accepted: Boolean? = null
        CategoriesViewModel(repository(renameTagResult = true)).renameTag("t1", " Rush ") { accepted = it }
        assertEquals(true, accepted)

        var refused: Boolean? = null
        CategoriesViewModel(repository(renameTagResult = false)).renameTag("t1", "Rush") { refused = it }
        assertEquals(false, refused)
    }

    @Test
    fun `renameTag refuses a blank name without calling the repository`() = runTest {
        val repository = repository()
        var result: Boolean? = null
        CategoriesViewModel(repository).renameTag("t1", "   ") { result = it }
        assertFalse(result!!)
        coVerify(exactly = 0) { repository.renameTag(any(), any()) }
    }
}
