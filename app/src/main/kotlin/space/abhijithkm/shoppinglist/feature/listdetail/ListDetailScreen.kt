package space.abhijithkm.shoppinglist.feature.listdetail

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.Item
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.core.model.Limits
import space.abhijithkm.shoppinglist.core.util.formatQuantityAndUnit
import space.abhijithkm.shoppinglist.feature.lists.ListEditDialog
import space.abhijithkm.shoppinglist.ui.NoInsets
import space.abhijithkm.shoppinglist.ui.components.displayName

/** Everything the detail screen can ask for. Defaults are no-ops so tests only wire what they check. */
data class ListDetailActions(
    val onAddItem: (String) -> Unit = {},
    val onSetBought: (itemId: String, bought: Boolean) -> Unit = { _, _ -> },
    val onDeleteItem: (String) -> Unit = {},
    val onRestoreItem: (String) -> Unit = {},
    val onUpdateItem: (ItemUpdate) -> Unit = {},
    val onMoveItem: (itemId: String, aboveId: String?, belowId: String?) -> Unit = { _, _, _ -> },
    val onClearCompleted: (onCleared: (List<String>) -> Unit) -> Unit = {},
    val onRestoreItems: (List<String>) -> Unit = {},
    val onCreateTag: (name: String, onCreated: (String) -> Unit) -> Unit = { _, _ -> },
    val onEditList: (name: String, color: Int?, icon: String?) -> Unit = { _, _, _ -> },
    val onArchiveList: () -> Unit = {},
    val onShowCompletedChange: (Boolean) -> Unit = {},
    val onGroupByCategoryChange: (Boolean) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onToggleCategoryFilter: (String) -> Unit = {},
    val onToggleTagFilter: (String) -> Unit = {},
    val onClearFilter: () -> Unit = {},
)

@Composable
fun ListDetailScreen(
    onBack: () -> Unit,
    viewModel: ListDetailViewModel = hiltViewModel(), // wired to Hilt; the content below is stateless
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The list was deleted or archived from elsewhere: there is nothing left to show.
    LaunchedEffect(state.isLoading, state.list) {
        if (!state.isLoading && state.list == null) onBack()
    }
    val actions = remember(viewModel) {
        ListDetailActions(
            onAddItem = viewModel::addItem,
            onSetBought = viewModel::setBought,
            onDeleteItem = viewModel::deleteItem,
            onRestoreItem = viewModel::restoreItem,
            onUpdateItem = viewModel::updateItem,
            onMoveItem = viewModel::moveItem,
            onClearCompleted = viewModel::clearCompleted,
            onRestoreItems = viewModel::restoreItems,
            onCreateTag = viewModel::createTag,
            onEditList = viewModel::editList,
            onArchiveList = viewModel::archiveList,
            onShowCompletedChange = viewModel::setShowCompleted,
            onGroupByCategoryChange = viewModel::setGroupByCategory,
            onQueryChange = viewModel::setQuery,
            onToggleCategoryFilter = viewModel::toggleCategoryFilter,
            onToggleTagFilter = viewModel::toggleTagFilter,
            onClearFilter = viewModel::clearFilter,
        )
    }
    ListDetailContent(state = state, actions = actions, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailContent(
    state: ListDetailUiState,
    actions: ListDetailActions,
    onBack: () -> Unit = {},
) {
    val list = state.list
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.undo)
    val itemDeletedMessage = stringResource(R.string.item_deleted)

    var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingList by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(state.filter.isActive) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingScrollToEnd by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun showUndo(message: String, onUndo: () -> Unit) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    fun deleteWithUndo(itemId: String) {
        actions.onDeleteItem(itemId)
        showUndo(itemDeletedMessage) { actions.onRestoreItem(itemId) }
    }

    // After a quick-add the new item is appended to the open items; bring it into view.
    LaunchedEffect(state.openItems.size) {
        if (pendingScrollToEnd && state.canReorder && state.openItems.isNotEmpty()) {
            val headerCount = 1 + if (filtersOpen) 1 else 0
            listState.animateScrollToItem(headerCount + state.openItems.size - 1)
        }
        pendingScrollToEnd = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        list?.icon?.let { Text(it, fontSize = 22.sp, modifier = Modifier.padding(end = 8.dp)) }
                        Text(text = list?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (filtersOpen) actions.onClearFilter()
                            filtersOpen = !filtersOpen
                        },
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_items))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit_list)) },
                                onClick = {
                                    menuOpen = false
                                    editingList = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.showCompleted) R.string.hide_completed_items else R.string.show_completed_items,
                                        ),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    actions.onShowCompletedChange(!state.showCompleted)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(if (state.groupByCategory) R.string.ungroup_items else R.string.group_by_category),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    actions.onGroupByCategoryChange(!state.groupByCategory)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_completed)) },
                                enabled = state.boughtCount > 0,
                                onClick = {
                                    menuOpen = false
                                    actions.onClearCompleted { ids ->
                                        if (ids.isNotEmpty()) {
                                            val message = resources.getQuantityString(R.plurals.completed_cleared, ids.size, ids.size)
                                            showUndo(message) { actions.onRestoreItems(ids) }
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.archive_list)) },
                                onClick = {
                                    menuOpen = false
                                    actions.onArchiveList()
                                    onBack()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            QuickAddBar(
                onAdd = { text ->
                    pendingScrollToEnd = true
                    actions.onAddItem(text)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = NoInsets,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading || list == null -> Unit
                state.totalCount == 0 -> CenteredMessage(
                    titleRes = R.string.items_empty_title,
                    bodyRes = R.string.items_empty_body,
                    modifier = Modifier.align(Alignment.Center).testTag("items_empty"),
                )
                else -> ItemsList(
                    state = state,
                    actions = actions,
                    listState = listState,
                    filtersOpen = filtersOpen,
                    onEdit = { editingItemId = it },
                    onDelete = ::deleteWithUndo,
                )
            }
        }
    }

    val editingItem = state.allItems.firstOrNull { it.id == editingItemId }
    if (editingItem != null) {
        ItemEditSheet(
            item = editingItem,
            categories = state.categories,
            tags = state.tags,
            onDismiss = { editingItemId = null },
            onSave = {
                actions.onUpdateItem(it)
                editingItemId = null
            },
            onDelete = {
                editingItemId = null
                deleteWithUndo(editingItem.id)
            },
            onCreateTag = actions.onCreateTag,
        )
    }

    if (editingList && list != null) {
        ListEditDialog(
            title = stringResource(R.string.edit_list),
            confirmLabel = stringResource(R.string.save),
            initialName = list.name,
            initialColor = list.color,
            initialIcon = list.icon,
            onDismiss = { editingList = false },
            onConfirm = { name, color, icon ->
                actions.onEditList(name, color, icon)
                editingList = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemsList(
    state: ListDetailUiState,
    actions: ListDetailActions,
    listState: LazyListState,
    filtersOpen: Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val categoryByKey = remember(state.categories) { state.categories.associateBy { it.key } }

    // Drag-and-drop works on a local copy and is committed to the database only when the drag ends.
    var order by remember(state.openItems) { mutableStateOf(state.openItems) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = order.indexOfFirst { it.id == from.key }
        val toIndex = order.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            order = order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }
    fun commitMove(itemId: String) {
        val index = order.indexOfFirst { it.id == itemId }
        if (index >= 0) actions.onMoveItem(itemId, order.getOrNull(index - 1)?.id, order.getOrNull(index + 1)?.id)
    }

    val noMatches = state.filter.isActive && state.openItems.isEmpty() && state.doneItems.isEmpty()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "progress") { ProgressHeader(boughtCount = state.boughtCount, totalCount = state.totalCount) }
        if (filtersOpen) item(key = "filters") { FilterPanel(state, actions) }

        if (noMatches) {
            item(key = "no-match") {
                Text(
                    text = stringResource(R.string.filter_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else if (state.canReorder) {
            items(order, key = { it.id }) { item ->
                ReorderableItem(reorderState, key = item.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "drag elevation")
                    Surface(shadowElevation = elevation, color = MaterialTheme.colorScheme.surface) {
                        SwipeableItemRow(
                            item = item,
                            category = categoryByKey[item.categoryKey],
                            onToggle = { actions.onSetBought(item.id, it) },
                            onEdit = { onEdit(item.id) },
                            onDelete = { onDelete(item.id) },
                            handle = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(onDragStopped = { commitMove(item.id) }),
                                ) {
                                    Icon(
                                        Icons.Filled.Menu,
                                        contentDescription = stringResource(R.string.reorder_item, item.name),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        } else {
            state.groups.forEach { group ->
                if (state.groupByCategory) {
                    item(key = "group-${group.category?.key ?: "none"}") { GroupHeader(group.category) }
                }
                items(group.items, key = { it.id }) { item ->
                    SwipeableItemRow(
                        item = item,
                        category = categoryByKey[item.categoryKey],
                        onToggle = { actions.onSetBought(item.id, it) },
                        onEdit = { onEdit(item.id) },
                        onDelete = { onDelete(item.id) },
                    )
                }
            }
        }

        if (!state.filter.isActive && state.openItems.isEmpty() && state.totalCount > 0) {
            item(key = "all-done") {
                CenteredMessage(
                    titleRes = R.string.all_done_title,
                    bodyRes = R.string.all_done_body,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("all_done"),
                )
            }
        }

        if (state.showCompleted && state.doneItems.isNotEmpty()) {
            item(key = "done-header") {
                SectionHeader(text = stringResource(R.string.completed_section, state.doneItems.size))
            }
            items(state.doneItems, key = { it.id }) { item ->
                SwipeableItemRow(
                    item = item,
                    category = categoryByKey[item.categoryKey],
                    onToggle = { actions.onSetBought(item.id, it) },
                    onEdit = { onEdit(item.id) },
                    onDelete = { onDelete(item.id) },
                )
            }
        }
    }
}

@Composable
private fun ProgressHeader(boughtCount: Int, totalCount: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.progress_summary, boughtCount, totalCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("progress_text"),
        )
        LinearProgressIndicator(
            progress = { if (totalCount == 0) 0f else boughtCount.toFloat() / totalCount },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(state: ListDetailUiState, actions: ListDetailActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.filter.query,
            onValueChange = actions.onQueryChange,
            placeholder = { Text(stringResource(R.string.search_items)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("item_search"),
        )
        val usedCategories = state.categories.filter { it.key in state.usedCategoryKeys }
        val usedTags = state.tags.filter { it.id in state.usedTagIds }
        if (usedCategories.isNotEmpty() || usedTags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                usedCategories.forEach { category ->
                    FilterChip(
                        selected = category.key in state.filter.categoryKeys,
                        onClick = { actions.onToggleCategoryFilter(category.key) },
                        label = { Text(category.displayName()) },
                    )
                }
                usedTags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in state.filter.tagIds,
                        onClick = { actions.onToggleTagFilter(tag.id) },
                        label = { Text("#${tag.name}") },
                    )
                }
            }
        }
        if (state.filter.isActive) {
            TextButton(onClick = actions.onClearFilter) { Text(stringResource(R.string.filter_clear)) }
        }
    }
}

@Composable
private fun GroupHeader(category: Category?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (category != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(category.color)))
        }
        Text(
            text = category?.displayName() ?: stringResource(R.string.uncategorised),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun CenteredMessage(@StringRes titleRes: Int, @StringRes bodyRes: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Swipe to the left deletes (with undo); the edit sheet offers Delete too for users who cannot swipe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableItemRow(
    item: Item,
    category: Category?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    handle: (@Composable () -> Unit)? = null,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        ItemRow(item = item, category = category, onToggle = onToggle, onEdit = onEdit, handle = handle)
    }
}

@Composable
private fun ItemRow(
    item: Item,
    category: Category?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    handle: (@Composable () -> Unit)? = null,
) {
    val markDescription = stringResource(
        if (item.isBought) R.string.mark_not_bought else R.string.mark_bought,
        item.name,
    )
    val details = listOfNotNull(
        formatQuantityAndUnit(item.quantity, item.unit),
        category?.displayName(),
        item.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#${it.name}" },
    ).joinToString(" · ")

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag("item_row")) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The checkbox and the text are separate targets so each has its own accessible name and action.
            Checkbox(
                checked = item.isBought,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = markDescription }.testTag("item_checkbox"),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.isBought) TextDecoration.LineThrough else null,
                    color = if (item.isBought) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!item.notes.isNullOrBlank()) {
                    Text(
                        text = item.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            handle?.invoke()
        }
    }
}

/** The fast inline entry: type, press the arrow or the keyboard's send key, the field stays ready for the next item. */
@Composable
private fun QuickAddBar(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom).union(WindowInsets.ime)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= Limits.ITEM_NAME) text = it },
                placeholder = { Text(stringResource(R.string.quick_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).testTag("quick_add_field"),
            )
            FilledIconButton(
                onClick = { submit() },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("quick_add_button"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_item))
            }
        }
    }
}
