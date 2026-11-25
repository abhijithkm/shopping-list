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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setShowCompleted(show: Boolean) {
        viewModelScope.launch { repository.setShowCompleted(show) }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setRemindersEnabled(enabled) }
    }

    fun setShoppingModeEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setShoppingModeEnabled(enabled) }
    }
}
