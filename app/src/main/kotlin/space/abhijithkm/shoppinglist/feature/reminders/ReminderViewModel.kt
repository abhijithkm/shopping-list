package space.abhijithkm.shoppinglist.feature.reminders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.repository.SurfacePrefsRepository
import space.abhijithkm.shoppinglist.surfaces.SurfaceRefresher

/**
 * One list's two device-only switches: its timed reminder and its "Shopping mode" notification. The list is the one named
 * by the screen's navigation argument. Writing is enough: the surface updater schedules alarms and posts notifications.
 */
@HiltViewModel
class ReminderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SurfacePrefsRepository,
    private val refresher: SurfaceRefresher,
) : ViewModel() {
    val listId: String = checkNotNull(savedStateHandle["listId"]) { "listId navigation argument is required" }

    val reminder: StateFlow<ReminderEntity?> = repository.observeReminder(listId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val shoppingMode: StateFlow<Boolean> = repository.observeShoppingMode(listId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun save(reminder: ReminderEntity) {
        viewModelScope.launch { repository.saveReminder(reminder) }
    }

    fun disable() {
        viewModelScope.launch { repository.disableReminder(listId) }
    }

    fun setShoppingMode(on: Boolean) {
        viewModelScope.launch { repository.setShoppingMode(listId, on) }
    }

    /** Redraws notifications and alarms now: the system permission changed, which no database write announces. */
    fun refreshSurfaces() {
        viewModelScope.launch { refresher.refreshAll() }
    }
}
