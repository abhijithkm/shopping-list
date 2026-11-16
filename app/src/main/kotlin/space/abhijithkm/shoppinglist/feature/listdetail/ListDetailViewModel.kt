package space.abhijithkm.shoppinglist.feature.listdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.Item
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.data.repository.SettingsRepository

/** Narrowing of the visible items: a name search plus categories and tags (any chosen tag matches). */
data class ItemFilter(
    val query: String = "",
    val categoryKeys: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
) {
    val isActive: Boolean get() = query.isNotBlank() || categoryKeys.isNotEmpty() || tagIds.isNotEmpty()
}

/** Open items of one category; [category] is null for uncategorised items (and for the single ungrouped block). */
data class ItemGroup(val category: Category?, val items: List<Item>)

data class ListDetailUiState(
    val isLoading: Boolean = true,
    /** Null once loading has finished means the list no longer exists. */
    val list: ListSummary? = null,
    val allItems: List<Item> = emptyList(),
    /** Filtered open items in manual order. */
    val openItems: List<Item> = emptyList(),
    /** [openItems] arranged for display: one block, or one block per category when grouping is on. */
    val groups: List<ItemGroup> = emptyList(),
    /** Filtered bought items, most recently bought first. */
    val doneItems: List<Item> = emptyList(),
    /** Unfiltered counts, for the progress indicator. */
    val totalCount: Int = 0,
    val boughtCount: Int = 0,
    val showCompleted: Boolean = true,
    val groupByCategory: Boolean = false,
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val usedCategoryKeys: Set<String> = emptySet(),
    val usedTagIds: Set<String> = emptySet(),
    val filter: ItemFilter = ItemFilter(),
) {
    /** Manual ordering only makes sense on the full, ungrouped list of open items. */
    val canReorder: Boolean get() = !groupByCategory && !filter.isActive
}

@HiltViewModel
class ListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LocalShoppingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle["listId"]) { "listId navigation argument is required" }
    private val filter = MutableStateFlow(ItemFilter())

    private data class Data(
        val list: ListSummary?,
        val items: List<Item>,
        val categories: List<Category>,
        val tags: List<Tag>,
    )

    val uiState: StateFlow<ListDetailUiState> = combine(
        combine(
            repository.observeList(listId),
            repository.observeItems(listId),
            repository.observeCategories(),
            repository.observeTags(),
            ::Data,
        ),
        settings.settings,
        filter,
    ) { data, appSettings, itemFilter ->
        buildListDetailState(data.list, data.items, data.categories, data.tags, appSettings, itemFilter)
            .copy(isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDetailUiState())

    fun addItem(text: String) {
        viewModelScope.launch { repository.addItem(listId, text) }
    }

    fun setBought(itemId: String, bought: Boolean) {
        viewModelScope.launch { repository.setItemBought(itemId, bought) }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch { repository.deleteItem(itemId) }
    }

    fun restoreItem(itemId: String) {
        viewModelScope.launch { repository.restoreItem(itemId) }
    }

    fun updateItem(update: ItemUpdate) {
        viewModelScope.launch { repository.updateItem(update) }
    }

    fun moveItem(itemId: String, aboveId: String?, belowId: String?) {
        viewModelScope.launch { repository.moveItem(itemId, aboveId, belowId) }
    }

    /** Deletes the bought items and reports their ids so the screen can offer undo. */
    fun clearCompleted(onCleared: (List<String>) -> Unit) {
        viewModelScope.launch { onCleared(repository.clearCompleted(listId)) }
    }

    fun restoreItems(itemIds: List<String>) {
        viewModelScope.launch { repository.restoreItems(itemIds) }
    }

    fun createTag(name: String, onCreated: (String) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { onCreated(repository.createTag(trimmed)) }
    }

    fun editList(name: String, color: Int?, icon: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.renameList(listId, trimmed)
            repository.setListAppearance(listId, color, icon)
        }
    }

    fun archiveList() {
        viewModelScope.launch { repository.setListArchived(listId, true) }
    }

    fun setShowCompleted(show: Boolean) {
        viewModelScope.launch { settings.setShowCompleted(show) }
    }

    fun setGroupByCategory(group: Boolean) {
        viewModelScope.launch { settings.setGroupByCategory(group) }
    }

    fun setQuery(query: String) = filter.update { it.copy(query = query) }

    fun toggleCategoryFilter(key: String) =
        filter.update { it.copy(categoryKeys = it.categoryKeys.toggled(key)) }

    fun toggleTagFilter(tagId: String) = filter.update { it.copy(tagIds = it.tagIds.toggled(tagId)) }

    fun clearFilter() = filter.update { ItemFilter() }
}

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value

/** Pure function so the filtering, sectioning and grouping rules are unit-testable without Android. */
internal fun buildListDetailState(
    list: ListSummary?,
    items: List<Item>,
    categories: List<Category>,
    tags: List<Tag>,
    settings: AppSettings,
    filter: ItemFilter,
): ListDetailUiState {
    val matching = items.filter { it.matches(filter) }
    val open = matching.filter { !it.isBought }
    val done = matching.filter { it.isBought }.sortedByDescending { it.boughtAt ?: 0L }
    val groups = if (settings.groupByCategory) groupByCategory(open, categories) else listOf(ItemGroup(null, open))
    return ListDetailUiState(
        isLoading = false,
        list = list,
        allItems = items,
        openItems = open,
        groups = groups,
        doneItems = done,
        totalCount = items.size,
        boughtCount = items.count { it.isBought },
        showCompleted = settings.showCompleted,
        groupByCategory = settings.groupByCategory,
        categories = categories,
        tags = tags,
        usedCategoryKeys = items.mapNotNull { it.categoryKey }.toSet(),
        usedTagIds = items.flatMap { item -> item.tags.map { it.id } }.toSet(),
        filter = filter,
    )
}

private fun Item.matches(filter: ItemFilter): Boolean {
    val query = filter.query.trim()
    if (query.isNotEmpty() && !name.contains(query, ignoreCase = true)) return false
    if (filter.categoryKeys.isNotEmpty() && categoryKey !in filter.categoryKeys) return false
    if (filter.tagIds.isNotEmpty() && tags.none { it.id in filter.tagIds }) return false
    return true
}

/** Groups follow the category order (built-ins, then custom); items with no known category go last. */
private fun groupByCategory(open: List<Item>, categories: List<Category>): List<ItemGroup> {
    val byKey = open.groupBy { it.categoryKey }
    val known = categories.map { it.key }.toSet()
    val ordered = categories.mapNotNull { category -> byKey[category.key]?.let { ItemGroup(category, it) } }
    val uncategorised = open.filter { it.categoryKey == null || it.categoryKey !in known }
    return if (uncategorised.isEmpty()) ordered else ordered + ItemGroup(null, uncategorised)
}
