package space.abhijithkm.shoppinglist.feature.lists

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.ListSort
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.MemberRole
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.data.repository.SettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelTest { // ViewModel logic with mocked repositories

    private fun summary(
        id: String,
        name: String,
        pinned: Boolean = false,
        updatedAt: Long = 0L,
        createdAt: Long = 0L,
    ) = ListSummary(
        id = id,
        name = name,
        color = null,
        isPinned = pinned,
        role = MemberRole.OWNER,
        totalCount = 0,
        boughtCount = 0,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun settingsRepository(sort: ListSort = ListSort.RECENT) = mockk<SettingsRepository> {
        every { settings } returns flowOf(AppSettings(listSort = sort))
        coEvery { setListSort(any()) } returns Unit
    }

    private fun repository(
        active: List<ListSummary> = emptyList(),
        archived: List<ListSummary> = emptyList(),
    ) = mockk<LocalShoppingRepository> {
        every { observeLists(archived = false) } returns flowOf(active)
        every { observeLists(archived = true) } returns flowOf(archived)
        coEvery { createList(any(), any(), any()) } returns "new-id"
        coEvery { renameList(any(), any()) } returns Unit
        coEvery { setListAppearance(any(), any(), any()) } returns Unit
        coEvery { setListPinned(any(), any()) } returns Unit
        coEvery { setListArchived(any(), any()) } returns Unit
        coEvery { deleteList(any()) } returns Unit
        coEvery { restoreList(any()) } returns Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `lists come pinned first then most recently updated and loading ends`() = runTest {
        val viewModel = ListsViewModel(
            repository(
                active = listOf(
                    summary("old", "Old", updatedAt = 1L),
                    summary("new", "New", updatedAt = 9L),
                    summary("pin", "Pinned", pinned = true, updatedAt = 2L),
                ),
            ),
            settingsRepository(),
        )

        val state = viewModel.uiState.first { !it.isLoading }

        assertEquals(listOf("pin", "new", "old"), state.lists.map { it.id })
        assertFalse(state.showArchived)
    }

    @Test
    fun `search filters by name ignoring case`() = runTest {
        val viewModel = ListsViewModel(
            repository(active = listOf(summary("a", "Monthly Groceries"), summary("b", "Hardware"))),
            settingsRepository(),
        )

        viewModel.setQuery("  GROCER ")
        val state = viewModel.uiState.first { !it.isLoading }

        assertEquals(listOf("a"), state.lists.map { it.id })
        assertEquals("  GROCER ", state.query)
    }

    @Test
    fun `sort order comes from settings`() = runTest {
        val viewModel = ListsViewModel(
            repository(active = listOf(summary("1", "banana"), summary("2", "Apple"), summary("3", "cherry"))),
            settingsRepository(ListSort.NAME),
        )

        val state = viewModel.uiState.first { !it.isLoading }

        assertEquals(listOf("Apple", "banana", "cherry"), state.lists.map { it.name })
        assertEquals(ListSort.NAME, state.sort)
    }

    @Test
    fun `archived mode shows the archived lists`() = runTest {
        val viewModel = ListsViewModel(
            repository(active = listOf(summary("a", "Active")), archived = listOf(summary("z", "Archived one"))),
            settingsRepository(),
        )

        viewModel.setShowArchived(true)
        val state = viewModel.uiState.first { !it.isLoading }

        assertTrue(state.showArchived)
        assertEquals(listOf("z"), state.lists.map { it.id })
    }

    @Test
    fun `choosing a sort order is saved to settings`() = runTest {
        val settings = settingsRepository()
        ListsViewModel(repository(), settings).setSort(ListSort.CREATED)
        coVerify(exactly = 1) { settings.setListSort(ListSort.CREATED) }
    }

    @Test
    fun `createList trims the name and passes colour and icon`() = runTest {
        val repository = repository()
        ListsViewModel(repository, settingsRepository()).createList("  Weekend  ", 0xFF112233.toInt(), "🎉")
        coVerify(exactly = 1) { repository.createList("Weekend", 0xFF112233.toInt(), "🎉") }
    }

    @Test
    fun `blank names are ignored when creating or editing`() = runTest {
        val repository = repository()
        val viewModel = ListsViewModel(repository, settingsRepository())
        viewModel.createList("   ", null, null)
        viewModel.editList("id", "  ", null, null)
        coVerify(exactly = 0) { repository.createList(any(), any(), any()) }
        coVerify(exactly = 0) { repository.renameList(any(), any()) }
    }

    @Test
    fun `editList renames then applies the appearance`() = runTest {
        val repository = repository()
        ListsViewModel(repository, settingsRepository()).editList("l1", " Renamed ", 5, "🏠")
        coVerify(exactly = 1) { repository.renameList("l1", "Renamed") }
        coVerify(exactly = 1) { repository.setListAppearance("l1", 5, "🏠") }
    }

    @Test
    fun `pin archive delete and restore are delegated`() = runTest {
        val repository = repository()
        val viewModel = ListsViewModel(repository, settingsRepository())

        viewModel.setPinned("l1", true)
        viewModel.setArchived("l1", true)
        viewModel.deleteList("l1")
        viewModel.restoreList("l1")

        coVerify(exactly = 1) { repository.setListPinned("l1", true) }
        coVerify(exactly = 1) { repository.setListArchived("l1", true) }
        coVerify(exactly = 1) { repository.deleteList("l1") }
        coVerify(exactly = 1) { repository.restoreList("l1") }
    }

    @Test
    fun `created order sorts newest first after pinned`() {
        val sorted = listOf(
            summary("a", "A", createdAt = 1L),
            summary("b", "B", createdAt = 3L),
            summary("c", "C", pinned = true, createdAt = 0L),
        ).filteredAndSorted("", ListSort.CREATED)
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }
}
