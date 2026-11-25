package space.abhijithkm.shoppinglist.feature.reminders

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat
import space.abhijithkm.shoppinglist.data.repository.SurfacePrefsRepository
import space.abhijithkm.shoppinglist.surfaces.SurfaceRefresher

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderViewModelTest {
    private val reminderFlow = MutableStateFlow<ReminderEntity?>(null)
    private val modeFlow = MutableStateFlow(false)

    private val repository = mockk<SurfacePrefsRepository> {
        every { observeReminder("l1") } returns reminderFlow
        every { observeShoppingMode("l1") } returns modeFlow
        coEvery { saveReminder(any()) } returns Unit
        coEvery { disableReminder(any()) } returns Unit
        coEvery { setShoppingMode(any(), any()) } returns Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val refresher = mockk<SurfaceRefresher> { coEvery { refreshAll() } returns Unit }

    private fun viewModel() = ReminderViewModel(SavedStateHandle(mapOf("listId" to "l1")), repository, refresher)

    @Test
    fun refreshSurfacesRedrawsEverythingNow() = runTest {
        viewModel().refreshSurfaces()

        coVerify { refresher.refreshAll() }
    }

    @Test
    fun theListIdComesFromTheNavigationArgument() {
        assertEquals("l1", viewModel().listId)
    }

    @Test
    fun noReminderUntilOneIsStored() = runTest {
        assertNull(viewModel().reminder.value)
    }

    @Test
    fun theReminderFlowShowsTheStoredReminder() = runTest {
        val vm = viewModel()
        reminderFlow.value = ReminderEntity(listId = "l1", repeat = ReminderRepeat.WEEKLY, hour = 18)

        val shown = vm.reminder.first { it != null }

        assertEquals(ReminderRepeat.WEEKLY, shown!!.repeat)
        assertEquals(18, shown.hour)
    }

    @Test
    fun saveWritesTheReminder() = runTest {
        val reminder = ReminderEntity(listId = "l1", repeat = ReminderRepeat.DAILY, hour = 8, minute = 15)

        viewModel().save(reminder)

        coVerify { repository.saveReminder(reminder) }
    }

    @Test
    fun disableTurnsTheReminderOff() = runTest {
        viewModel().disable()

        coVerify { repository.disableReminder("l1") }
    }

    @Test
    fun shoppingModeFollowsTheRepositoryAndCanBeSwitched() = runTest {
        val vm = viewModel()
        assertFalse(vm.shoppingMode.value)

        modeFlow.value = true
        assertTrue(vm.shoppingMode.first { it })

        vm.setShoppingMode(false)
        coVerify { repository.setShoppingMode("l1", false) }
    }
}
