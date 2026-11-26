package space.abhijithkm.shoppinglist.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.ThemeMode
import space.abhijithkm.shoppinglist.data.repository.SettingsRepository
import space.abhijithkm.shoppinglist.surfaces.SurfaceRefresher

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val refresher: SurfaceRefresher,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setShowCompleted(show: Boolean) {
        viewModelScope.launch { repository.setShowCompleted(show) }
    }

    fun setNotifyInvitations(enabled: Boolean) {
        viewModelScope.launch { repository.setNotifyInvitations(enabled) }
    }

    fun setNotifySharedActivity(enabled: Boolean) {
        viewModelScope.launch { repository.setNotifySharedActivity(enabled) }
    }

    // These switches live in DataStore, which the surface updater does not watch: redraw the notifications and alarms ourselves.
    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setRemindersEnabled(enabled)
            refresher.refreshAll()
        }
    }

    fun setShoppingModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setShoppingModeEnabled(enabled)
            refresher.refreshAll()
        }
    }

    /** Redraws everything now, e.g. after the owner re-enabled notifications in the system settings. */
    fun refreshSurfaces() {
        viewModelScope.launch { refresher.refreshAll() }
    }
}
