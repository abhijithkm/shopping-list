package space.abhijithkm.shoppinglist.feature.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository

data class ListsUiState(
    val lists: List<ListSummary> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repository: LocalShoppingRepository,
) : ViewModel() {

    val uiState: StateFlow<ListsUiState> = repository.observeLists()
        .map { lists -> ListsUiState(lists = lists, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState())

    fun createList(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createList(trimmed) }
    }
}
