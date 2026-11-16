package space.abhijithkm.shoppinglist.feature.lists

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.MemberRole
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelTest {
    private val summary = ListSummary(
        id = "l1",
        name = "Monthly Groceries",
        color = null,
        isPinned = false,
        role = MemberRole.OWNER,
        totalCount = 6,
        boughtCount = 2,
        updatedAt = 10L,
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `exposes lists from the repository and stops loading`() = runTest {
        val repository = mockk<LocalShoppingRepository> {
            every { observeLists() } returns flowOf(listOf(summary))
        }
        val viewModel = ListsViewModel(repository)

        viewModel.uiState.test {
            var state = awaitItem()
            if (state.isLoading) state = awaitItem()
            assertEquals(listOf(summary), state.lists)
            assertFalse(state.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `createList trims the name and delegates`() = runTest {
        val repository = mockk<LocalShoppingRepository> {
            every { observeLists() } returns flowOf(emptyList())
            coEvery { createList(any()) } returns "new-id"
        }
        ListsViewModel(repository).createList("  Weekend  ")
        coVerify(exactly = 1) { repository.createList("Weekend") }
    }

    @Test
    fun `blank names are ignored`() = runTest {
        val repository = mockk<LocalShoppingRepository> {
            every { observeLists() } returns flowOf(emptyList())
            coEvery { createList(any()) } returns "new-id"
        }
        ListsViewModel(repository).createList("   ")
        coVerify(exactly = 0) { repository.createList(any()) }
    }
}
