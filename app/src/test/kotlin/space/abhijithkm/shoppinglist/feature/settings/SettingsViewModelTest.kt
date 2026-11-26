package space.abhijithkm.shoppinglist.feature.settings

import io.mockk.Ordering
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
import org.junit.Before
import org.junit.Test
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.data.repository.SettingsRepository
import space.abhijithkm.shoppinglist.surfaces.SurfaceRefresher

/**
 * The two notification switches live in DataStore, not Room, so nothing tells the surface updater they changed: the view model
 * must redraw the notifications and alarms itself (a switch turned off must not leave the ongoing notification up).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val repository = mockk<SettingsRepository> {
        every { settings } returns flowOf(AppSettings())
        coEvery { setRemindersEnabled(any()) } returns Unit
        coEvery { setShoppingModeEnabled(any()) } returns Unit
    }
    private val refresher = mockk<SurfaceRefresher> { coEvery { refreshAll() } returns Unit }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(repository, refresher)

    @Test
    fun switchingShoppingModeOffRedrawsTheNotifications() = runTest {
        viewModel().setShoppingModeEnabled(false)

        coVerify(ordering = Ordering.ORDERED) {
            repository.setShoppingModeEnabled(false)
            refresher.refreshAll()
        }
    }

    @Test
    fun switchingRemindersRedrawsTheAlarms() = runTest {
        viewModel().setRemindersEnabled(true)

        coVerify(ordering = Ordering.ORDERED) {
            repository.setRemindersEnabled(true)
            refresher.refreshAll()
        }
    }

    @Test
    fun refreshSurfacesRedrawsEverythingNow() = runTest {
        viewModel().refreshSurfaces()

        coVerify { refresher.refreshAll() }
    }
}
