package space.abhijithkm.shoppinglist.feature.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository

data class CategoriesUiState(
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: LocalShoppingRepository,
) : ViewModel() {

    val uiState: StateFlow<CategoriesUiState> = combine(
        repository.observeCategories(),
        repository.observeTags(),
    ) { categories, tags ->
        CategoriesUiState(categories = categories, tags = tags, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun createCategory(name: String, color: Int?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createCategory(trimmed, color) }
    }

    /** A null [name] or [color] leaves that attribute unchanged. */
    fun updateCategory(key: String, name: String?, color: Int?) {
        val trimmed = name?.trim()
        if (trimmed != null && trimmed.isEmpty()) return
        viewModelScope.launch { repository.updateCategory(key, trimmed, color) }
    }

    /** Deletes a custom category, or resets a built-in one to its original name and colour. */
    fun deleteCategory(key: String) {
        viewModelScope.launch { repository.deleteCategory(key) }
    }

    /** Reports false when the name is blank or another tag already has it. */
    fun renameTag(id: String, name: String, onResult: (Boolean) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(false)
            return
        }
        viewModelScope.launch { onResult(repository.renameTag(id, trimmed)) }
    }

    fun deleteTag(id: String) {
        viewModelScope.launch { repository.deleteTag(id) }
    }
}
