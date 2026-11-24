package space.abhijithkm.shoppinglist.feature.lists

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.Limits
import space.abhijithkm.shoppinglist.core.model.ListSort
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.ui.NoInsets
import space.abhijithkm.shoppinglist.ui.components.ColorPicker
import space.abhijithkm.shoppinglist.ui.components.IconPicker

/** Everything the Lists screen can ask for. Defaults are no-ops so tests only wire what they check. */
data class ListsActions(
    val onCreate: (name: String, color: Int?, icon: String?) -> Unit = { _, _, _ -> },
    val onOpen: (listId: String) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSortChange: (ListSort) -> Unit = {},
    val onShowArchivedChange: (Boolean) -> Unit = {},
    val onSetPinned: (listId: String, pinned: Boolean) -> Unit = { _, _ -> },
    val onEdit: (listId: String, name: String, color: Int?, icon: String?) -> Unit = { _, _, _, _ -> },
    val onSetArchived: (listId: String, archived: Boolean) -> Unit = { _, _ -> },
    val onDelete: (listId: String) -> Unit = {},
    val onRestore: (listId: String) -> Unit = {},
    val onOpenInvitations: () -> Unit = {},
)

@Composable
fun ListsScreen(
    onOpenList: (String) -> Unit = {},
    onOpenInvitations: () -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(), // wired to Hilt; the content below is stateless
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenList, onOpenInvitations) {
        ListsActions(
            onCreate = viewModel::createList,
            onOpen = onOpenList,
            onQueryChange = viewModel::setQuery,
            onSortChange = viewModel::setSort,
            onShowArchivedChange = viewModel::setShowArchived,
            onSetPinned = viewModel::setPinned,
            onEdit = viewModel::editList,
            onSetArchived = viewModel::setArchived,
            onDelete = viewModel::deleteList,
            onRestore = viewModel::restoreList,
            onOpenInvitations = onOpenInvitations,
        )
    }
    ListsScreenContent(state = state, actions = actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreenContent(
    state: ListsUiState,
    actions: ListsActions,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.undo)
    val deletedMessage = stringResource(R.string.list_deleted)
    val archivedMessage = stringResource(R.string.list_archived)
    val unarchivedMessage = stringResource(R.string.list_unarchived)

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(query = state.query, onQueryChange = actions.onQueryChange)
                    } else {
                        Text(stringResource(if (state.showArchived) R.string.archived_lists else R.string.nav_lists))
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(
                            onClick = {
                                searching = false
                                actions.onQueryChange("")
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close_search))
                        }
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = actions.onOpenInvitations) {
                            BadgedBox(badge = { if (state.inboxCount > 0) Badge { Text(state.inboxCount.toString()) } }) {
                                Icon(Icons.Filled.Notifications, contentDescription = stringResource(R.string.inbox_action))
                            }
                        }
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            ListSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(sort.labelRes())) },
                                    leadingIcon = { if (state.sort == sort) Icon(Icons.Filled.Check, contentDescription = null) },
                                    onClick = {
                                        actions.onSortChange(sort)
                                        menuOpen = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(if (state.showArchived) R.string.show_active else R.string.show_archived)) },
                                onClick = {
                                    actions.onShowArchivedChange(!state.showArchived)
                                    menuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!state.showArchived) {
                // Content-slot overload on purpose: the icon/text overload leaves the button unnamed for TalkBack.
                ExtendedFloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.create_list))
                }
            }
        },
        contentWindowInsets = NoInsets,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> Unit
                state.lists.isEmpty() -> EmptyLists(
                    titleRes = when {
                        state.query.isNotBlank() -> R.string.lists_no_results
                        state.showArchived -> R.string.lists_archived_empty
                        else -> R.string.lists_empty_title
                    },
                    showHint = state.query.isBlank() && !state.showArchived,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.lists, key = { it.id }) { list ->
                        ListCard(
                            list = list,
                            onClick = { actions.onOpen(list.id) },
                            onTogglePin = { actions.onSetPinned(list.id, !list.isPinned) },
                            onEdit = { editingId = list.id },
                            onToggleArchive = {
                                val archive = !list.isArchived
                                actions.onSetArchived(list.id, archive)
                                showUndo(if (archive) archivedMessage else unarchivedMessage) {
                                    actions.onSetArchived(list.id, !archive)
                                }
                            },
                            onDelete = {
                                actions.onDelete(list.id)
                                showUndo(deletedMessage) { actions.onRestore(list.id) }
                            },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        ListEditDialog(
            title = stringResource(R.string.create_list),
            confirmLabel = stringResource(R.string.create),
            initialName = "",
            initialColor = null,
            initialIcon = null,
            onDismiss = { creating = false },
            onConfirm = { name, color, icon ->
                actions.onCreate(name, color, icon)
                creating = false
            },
        )
    }

    val editing = state.lists.firstOrNull { it.id == editingId }
    if (editing != null) {
        ListEditDialog(
            title = stringResource(R.string.edit_list),
            confirmLabel = stringResource(R.string.save),
            initialName = editing.name,
            initialColor = editing.color,
            initialIcon = editing.icon,
            onDismiss = { editingId = null },
            onConfirm = { name, color, icon ->
                actions.onEdit(editing.id, name, color, icon)
                editingId = null
            },
        )
    }
}

@StringRes
private fun ListSort.labelRes(): Int = when (this) {
    ListSort.RECENT -> R.string.sort_recent
    ListSort.NAME -> R.string.sort_name
    ListSort.CREATED -> R.string.sort_created
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_lists)) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("search_field"),
    )
}

@Composable
private fun EmptyLists(@StringRes titleRes: Int, showHint: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp).testTag("lists_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (showHint) {
            Text(
                text = stringResource(R.string.lists_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ListCard(
    list: ListSummary,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val updated = remember(list.updatedAt) {
        DateUtils.getRelativeTimeSpanString(list.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth().testTag("list_card")) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ListBadge(list)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = list.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (list.isPinned) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.list_pinned),
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (list.totalCount == 0) {
                    Text(
                        text = stringResource(R.string.list_no_items),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.list_progress, list.boughtCount, list.totalCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { list.boughtCount.toFloat() / list.totalCount },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = stringResource(R.string.list_updated, updated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (list.isShared) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = list.ownerName?.let { stringResource(R.string.list_shared_by, it) } ?: stringResource(R.string.list_shared),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp).testTag("shared_marker"),
                        )
                    }
                }
            }
            // Only the owner manages the list itself (pin, rename, archive, delete); editors work on its items.
            if (list.canManage) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.list_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (list.isPinned) R.string.unpin else R.string.pin)) },
                        onClick = {
                            menuOpen = false
                            onTogglePin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_list)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (list.isArchived) R.string.unarchive else R.string.archive)) },
                        onClick = {
                            menuOpen = false
                            onToggleArchive()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** The list's emoji on a tinted disc, or a plain colour dot, or nothing when neither is set. */
@Composable
private fun ListBadge(list: ListSummary) {
    val tint = list.color?.let { Color(it) }
    when {
        list.icon != null -> Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint?.copy(alpha = 0.2f) ?: MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(list.icon, fontSize = 22.sp)
        }
        tint != null -> Box(Modifier.size(14.dp).clip(CircleShape).background(tint))
    }
}

@Composable
internal fun ListEditDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialColor: Int?,
    initialIcon: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Int?, icon: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var color by rememberSaveable { mutableStateOf(initialColor) }
    var icon by rememberSaveable { mutableStateOf(initialIcon) }
    val focusRequester = remember { FocusRequester() }
    // Focus can fail if the field is not attached yet; the dialog still works without it.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Limits.LIST_NAME) name = it },
                    label = { Text(stringResource(R.string.list_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name, color, icon) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("list_name_input"),
                )
                Text(stringResource(R.string.list_color_label), style = MaterialTheme.typography.labelLarge)
                ColorPicker(selected = color, onSelected = { color = it })
                Text(stringResource(R.string.list_icon_label), style = MaterialTheme.typography.labelLarge)
                IconPicker(selected = icon, onSelected = { icon = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, color, icon) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
