package space.abhijithkm.shoppinglist.feature.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.core.model.ListSort
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.data.repository.SettingsRepository

/** Everything the Lists screen renders; [lists] is already filtered by the search query and sorted. */
data class ListsUiState(
    val lists: List<ListSummary> = emptyList(),
    val isLoading: Boolean = true,
    val query: String = "",
    val sort: ListSort = ListSort.RECENT,
    val showArchived: Boolean = false,
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repository: LocalShoppingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val showArchived = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ListsUiState> = combine(
        showArchived.flatMapLatest { archived -> repository.observeLists(archived = archived) },
        query,
        showArchived,
        settings.settings.map { it.listSort }.distinctUntilChanged(),
    ) { lists, q, archived, sort ->
        ListsUiState(
            lists = lists.filteredAndSorted(q, sort),
            isLoading = false,
            query = q,
            sort = sort,
            showArchived = archived,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setShowArchived(value: Boolean) {
        showArchived.value = value
    }

    fun setSort(sort: ListSort) {
        viewModelScope.launch { settings.setListSort(sort) }
    }

    fun createList(name: String, color: Int?, icon: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createList(trimmed, color, icon) }
    }

    fun editList(id: String, name: String, color: Int?, icon: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.renameList(id, trimmed)
            repository.setListAppearance(id, color, icon)
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.setListPinned(id, pinned) }
    }

    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repository.setListArchived(id, archived) }
    }

    fun deleteList(id: String) {
        viewModelScope.launch { repository.deleteList(id) }
    }

    fun restoreList(id: String) {
        viewModelScope.launch { repository.restoreList(id) }
    }
}

/** Case-insensitive name search, then pinned lists first, then the chosen order. */
internal fun List<ListSummary>.filteredAndSorted(query: String, sort: ListSort): List<ListSummary> {
    val needle = query.trim()
    val filtered = if (needle.isEmpty()) this else filter { it.name.contains(needle, ignoreCase = true) }
    val pinnedFirst = compareByDescending<ListSummary> { it.isPinned }
    val comparator = when (sort) {
        ListSort.RECENT -> pinnedFirst.thenByDescending { it.updatedAt }
        ListSort.NAME -> pinnedFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        ListSort.CREATED -> pinnedFirst.thenByDescending { it.createdAt }
    }
    return filtered.sortedWith(comparator)
}
