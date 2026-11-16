package space.abhijithkm.shoppinglist.feature.listdetail

import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.DefaultCategory
import space.abhijithkm.shoppinglist.core.model.Item
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.MemberRole
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.data.repository.SettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailViewModelTest {
    private val veg = Category("vegetables", null, 1, DefaultCategory.VEGETABLES, false)
    private val dairy = Category("dairy", null, 2, DefaultCategory.DAIRY, false)
    private val pets = Category("c-pets", "Pets", 3, null, false)
    private val categories = listOf(veg, dairy, pets)
    private val urgent = Tag("t1", "Urgent")
    private val weekend = Tag("t2", "Weekend")
    private val tags = listOf(urgent, weekend)
    private val list = ListSummary(
        id = "l1",
        name = "Groceries",
        color = null,
        isPinned = false,
        role = MemberRole.OWNER,
        totalCount = 0,
        boughtCount = 0,
        updatedAt = 0L,
    )

    private fun item(
        id: String,
        bought: Boolean = false,
        category: String? = null,
        itemTags: List<Tag> = emptyList(),
        boughtAt: Long? = null,
        order: Double = 0.0,
    ) = Item(
        id = id,
        listId = "l1",
        name = id,
        quantity = null,
        unit = null,
        categoryKey = category,
        notes = null,
        isBought = bought,
        boughtAt = boughtAt,
        sortOrder = order,
        tags = itemTags,
    )

    private fun build(
        items: List<Item>,
        filter: ItemFilter = ItemFilter(),
        settings: AppSettings = AppSettings(),
    ) = buildListDetailState(list, items, categories, tags, settings, filter)

    // ----------------------------------------------------- pure state building

    @Test
    fun `open and done are split and done shows the most recently bought first`() {
        val state = build(
            listOf(
                item("open1", order = 1.0),
                item("old", bought = true, boughtAt = 10L),
                item("new", bought = true, boughtAt = 20L),
                item("open2", order = 2.0),
            ),
        )
        assertEquals(listOf("open1", "open2"), state.openItems.map { it.id })
        assertEquals(listOf("new", "old"), state.doneItems.map { it.id })
        assertEquals(4, state.totalCount)
        assertEquals(2, state.boughtCount)
    }

    @Test
    fun `counts stay unfiltered while the sections are filtered`() {
        val state = build(listOf(item("a", bought = true), item("b"), item("c")), ItemFilter(query = "b"))
        assertEquals(listOf("b"), state.openItems.map { it.id })
        assertTrue(state.doneItems.isEmpty())
        assertEquals(3, state.totalCount)
        assertEquals(1, state.boughtCount)
    }

    @Test
    fun `search ignores case and surrounding spaces`() {
        val state = build(listOf(item("Milk"), item("Bread")), ItemFilter(query = "  mILK "))
        assertEquals(listOf("Milk"), state.openItems.map { it.id })
    }

    @Test
    fun `category filter keeps only the chosen categories`() {
        val state = build(
            listOf(item("a", category = "dairy"), item("b", category = "vegetables"), item("c")),
            ItemFilter(categoryKeys = setOf("dairy", "c-pets")),
        )
        assertEquals(listOf("a"), state.openItems.map { it.id })
    }

    @Test
    fun `tag filter matches any chosen tag`() {
        val state = build(
            listOf(item("a", itemTags = listOf(urgent)), item("b", itemTags = listOf(weekend)), item("c")),
            ItemFilter(tagIds = setOf("t1", "t2")),
        )
        assertEquals(listOf("a", "b"), state.openItems.map { it.id })
    }

    @Test
    fun `grouping follows category order and puts uncategorised last`() {
        val state = build(
            listOf(
                item("p", category = "c-pets", order = 1.0),
                item("d", category = "dairy", order = 2.0),
                item("none", order = 3.0),
                item("ghost", category = "deleted-category", order = 4.0),
            ),
            settings = AppSettings(groupByCategory = true),
        )
        assertEquals(listOf("dairy", "c-pets", null), state.groups.map { it.category?.key })
        assertEquals(listOf("none", "ghost"), state.groups.last().items.map { it.id })
    }

    @Test
    fun `without grouping there is one block in manual order`() {
        val state = build(listOf(item("a", order = 1.0), item("b", order = 2.0)))
        assertEquals(1, state.groups.size)
        assertNull(state.groups.single().category)
        assertEquals(listOf("a", "b"), state.groups.single().items.map { it.id })
    }

    @Test
    fun `reordering needs no grouping and no filter`() {
        val items = listOf(item("a"), item("b"))
        assertTrue(build(items).canReorder)
        assertFalse(build(items, settings = AppSettings(groupByCategory = true)).canReorder)
        assertFalse(build(items, ItemFilter(query = "a")).canReorder)
    }

    @Test
    fun `filter chips come from all items even when the list is filtered`() {
        val state = build(
            listOf(item("a", category = "dairy", itemTags = listOf(urgent)), item("b", category = "c-pets")),
            ItemFilter(query = "a"),
        )
        assertEquals(setOf("dairy", "c-pets"), state.usedCategoryKeys)
        assertEquals(setOf("t1"), state.usedTagIds)
    }

    @Test
    fun `the completed flag follows the setting`() {
        assertFalse(build(emptyList(), settings = AppSettings(showCompleted = false)).showCompleted)
    }

    // ---------------------------------------------------------- view model

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun repository(items: List<Item> = emptyList(), listExists: Boolean = true) = mockk<LocalShoppingRepository> {
        every { observeList("l1") } returns flowOf(if (listExists) list else null)
        every { observeItems("l1") } returns flowOf(items)
        every { observeCategories() } returns flowOf(categories)
        every { observeTags() } returns flowOf(tags)
        coEvery { addItem(any(), any()) } returns "new"
        coEvery { setItemBought(any(), any()) } returns Unit
        coEvery { deleteItem(any()) } returns Unit
        coEvery { restoreItem(any()) } returns Unit
        coEvery { updateItem(any()) } returns Unit
        coEvery { moveItem(any(), any(), any()) } returns Unit
        coEvery { clearCompleted("l1") } returns listOf("a", "c")
        coEvery { restoreItems(any()) } returns Unit
        coEvery { createTag(any()) } returns "t-new"
        coEvery { renameList(any(), any()) } returns Unit
        coEvery { setListAppearance(any(), any(), any()) } returns Unit
        coEvery { setListArchived(any(), any()) } returns Unit
    }

    private fun settings() = mockk<SettingsRepository> {
        every { settings } returns flowOf(AppSettings())
        coEvery { setShowCompleted(any()) } returns Unit
        coEvery { setGroupByCategory(any()) } returns Unit
    }

    private fun viewModel(repository: LocalShoppingRepository, settings: SettingsRepository = settings()) =
        ListDetailViewModel(SavedStateHandle(mapOf("listId" to "l1")), repository, settings)

    @Test
    fun `state loads the list and its items`() = runTest {
        val state = viewModel(repository(listOf(item("a"), item("b", bought = true)))).uiState.first { !it.isLoading }
        assertEquals("Groceries", state.list?.name)
        assertEquals(2, state.totalCount)
        assertEquals(1, state.boughtCount)
    }

    @Test
    fun `a missing list shows up as no list once loading ends`() = runTest {
        val state = viewModel(repository(listExists = false)).uiState.first { !it.isLoading }
        assertNull(state.list)
    }

    @Test
    fun `item actions are delegated to the repository`() = runTest {
        val repository = repository()
        val viewModel = viewModel(repository)

        viewModel.addItem("2 kg milk")
        viewModel.setBought("a", true)
        viewModel.deleteItem("a")
        viewModel.restoreItem("a")
        viewModel.moveItem("a", "x", "y")
        viewModel.restoreItems(listOf("a", "b"))
        val update = ItemUpdate("a", "Oat milk", 2.0, "l", "dairy", null, setOf("t1"))
        viewModel.updateItem(update)

        coVerify(exactly = 1) { repository.addItem("l1", "2 kg milk") }
        coVerify(exactly = 1) { repository.setItemBought("a", true) }
        coVerify(exactly = 1) { repository.deleteItem("a") }
        coVerify(exactly = 1) { repository.restoreItem("a") }
        coVerify(exactly = 1) { repository.moveItem("a", "x", "y") }
        coVerify(exactly = 1) { repository.restoreItems(listOf("a", "b")) }
        coVerify(exactly = 1) { repository.updateItem(update) }
    }

    @Test
    fun `clearCompleted reports the cleared ids so the screen can offer undo`() = runTest {
        var cleared: List<String>? = null
        viewModel(repository()).clearCompleted { cleared = it }
        assertEquals(listOf("a", "c"), cleared)
    }

    @Test
    fun `createTag trims the name and reports the id`() = runTest {
        val repository = repository()
        var created: String? = null
        val viewModel = viewModel(repository)

        viewModel.createTag("  Weekend  ") { created = it }
        viewModel.createTag("   ") { created = "must not be called" }

        assertEquals("t-new", created)
        coVerify(exactly = 1) { repository.createTag("Weekend") }
        coVerify(exactly = 0) { repository.createTag("") }
    }

    @Test
    fun `list level actions are delegated`() = runTest {
        val repository = repository()
        val settings = settings()
        val viewModel = viewModel(repository, settings)

        viewModel.editList("  Monthly ", 7, "🏠")
        viewModel.editList("   ", null, null)
        viewModel.archiveList()
        viewModel.setShowCompleted(false)
        viewModel.setGroupByCategory(true)

        coVerify(exactly = 1) { repository.renameList("l1", "Monthly") }
        coVerify(exactly = 1) { repository.setListAppearance("l1", 7, "🏠") }
        coVerify(exactly = 1) { repository.setListArchived("l1", true) }
        coVerify(exactly = 1) { settings.setShowCompleted(false) }
        coVerify(exactly = 1) { settings.setGroupByCategory(true) }
    }

    @Test
    fun `filter toggles narrow the visible items and clear resets them`() = runTest {
        val viewModel = viewModel(
            repository(listOf(item("a", category = "dairy", itemTags = listOf(urgent)), item("b"))),
        )

        viewModel.toggleCategoryFilter("dairy")
        var state = viewModel.uiState.first { !it.isLoading }
        assertEquals(listOf("a"), state.openItems.map { it.id })
        assertTrue(state.filter.isActive)

        viewModel.toggleCategoryFilter("dairy")
        viewModel.toggleTagFilter("t1")
        state = viewModel.uiState.first { !it.isLoading }
        assertEquals(setOf("t1"), state.filter.tagIds)
        assertTrue(state.filter.categoryKeys.isEmpty())

        viewModel.setQuery("zzz")
        state = viewModel.uiState.first { !it.isLoading }
        assertTrue(state.openItems.isEmpty())

        viewModel.clearFilter()
        state = viewModel.uiState.first { !it.isLoading }
        assertFalse(state.filter.isActive)
        assertEquals(2, state.openItems.size)
    }
}
