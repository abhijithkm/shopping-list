package space.abhijithkm.shoppinglist.feature.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.Limits
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.data.repository.DEFAULT_CUSTOM_CATEGORY_COLOR
import space.abhijithkm.shoppinglist.ui.NoInsets
import space.abhijithkm.shoppinglist.ui.components.ColorPicker
import space.abhijithkm.shoppinglist.ui.components.displayName

/** Everything the screen can ask for. Defaults are no-ops so tests only wire what they check. */
data class CategoriesActions(
    val onCreateCategory: (name: String, color: Int?) -> Unit = { _, _ -> },
    /** A null name or colour means "unchanged". */
    val onUpdateCategory: (key: String, name: String?, color: Int?) -> Unit = { _, _, _ -> },
    val onDeleteCategory: (key: String) -> Unit = {},
    val onRenameTag: (id: String, name: String, onResult: (Boolean) -> Unit) -> Unit = { _, _, onResult -> onResult(true) },
    val onDeleteTag: (id: String) -> Unit = {},
)

@Composable
fun CategoriesScreen(viewModel: CategoriesViewModel = hiltViewModel()) { // wired to Hilt; content below is stateless
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CategoriesScreenContent(
        state = state,
        actions = CategoriesActions(
            onCreateCategory = viewModel::createCategory,
            onUpdateCategory = viewModel::updateCategory,
            onDeleteCategory = viewModel::deleteCategory,
            onRenameTag = viewModel::renameTag,
            onDeleteTag = viewModel::deleteTag,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreenContent(state: CategoriesUiState, actions: CategoriesActions) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingCategoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTagId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_categories)) }) },
        floatingActionButton = {
            // Content-slot overload on purpose: the icon/text overload leaves the button unnamed for TalkBack.
            ExtendedFloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.category_new))
            }
        },
        contentWindowInsets = NoInsets,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.categories_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(state.categories, key = { it.key }) { category ->
                CategoryRow(category = category, onClick = { editingCategoryKey = category.key })
            }
            item(key = "tags-title") {
                Text(
                    text = stringResource(R.string.tags_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
                )
            }
            if (state.tags.isEmpty()) {
                item(key = "tags-empty") {
                    Text(
                        text = stringResource(R.string.tags_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp).testTag("tags_empty"),
                    )
                }
            } else {
                items(state.tags, key = { "tag-${it.id}" }) { tag ->
                    TagRow(tag = tag, onClick = { editingTagId = tag.id })
                }
            }
        }
    }

    if (creating) {
        CategoryEditDialog(
            category = null,
            onDismiss = { creating = false },
            onSave = { name, color, _ ->
                actions.onCreateCategory(name, color)
                creating = false
            },
            onDeleteOrReset = null,
        )
    }

    val editingCategory = state.categories.firstOrNull { it.key == editingCategoryKey }
    if (editingCategory != null) {
        CategoryEditDialog(
            category = editingCategory,
            onDismiss = { editingCategoryKey = null },
            onSave = { name, color, nameChanged ->
                actions.onUpdateCategory(editingCategory.key, name.takeIf { nameChanged }, color.takeIf { it != editingCategory.color })
                editingCategoryKey = null
            },
            onDeleteOrReset = if (editingCategory.isDefault && !editingCategory.isCustomized) {
                null
            } else {
                {
                    actions.onDeleteCategory(editingCategory.key)
                    editingCategoryKey = null
                }
            },
        )
    }

    val editingTag = state.tags.firstOrNull { it.id == editingTagId }
    if (editingTag != null) {
        TagEditDialog(
            tag = editingTag,
            onDismiss = { editingTagId = null },
            onRename = { name, onResult -> actions.onRenameTag(editingTag.id, name, onResult) },
            onDelete = {
                actions.onDeleteTag(editingTag.id)
                editingTagId = null
            },
        )
    }
}

@Composable
private fun CategoryRow(category: Category, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .testTag("category_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(category.color)))
        Text(
            text = category.displayName(),
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        if (category.isCustomized) {
            Text(
                text = stringResource(R.string.category_customized),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TagRow(tag: Tag, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .testTag("tag_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "#${tag.name}")
    }
}

/**
 * Create ([category] null) or edit a category. [onSave] receives the name, the colour and whether
 * the name was changed. [onDeleteOrReset] is null when there is nothing to delete or reset.
 */
@Composable
private fun CategoryEditDialog(
    category: Category?,
    onDismiss: () -> Unit,
    onSave: (name: String, color: Int, nameChanged: Boolean) -> Unit,
    onDeleteOrReset: (() -> Unit)?,
) {
    val initialName = category?.displayName().orEmpty()
    var name by rememberSaveable { mutableStateOf(initialName) }
    var color by rememberSaveable { mutableIntStateOf(category?.color ?: DEFAULT_CUSTOM_CATEGORY_COLOR) }
    val isBuiltIn = category?.isDefault == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (category == null) R.string.category_new else R.string.category_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Limits.CATEGORY_NAME) name = it },
                    label = { Text(stringResource(R.string.category_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("category_name_input"),
                )
                Text(stringResource(R.string.list_color_label), style = MaterialTheme.typography.labelLarge)
                ColorPicker(selected = color, onSelected = { color = it ?: DEFAULT_CUSTOM_CATEGORY_COLOR }, allowNone = false)
                if (onDeleteOrReset != null) {
                    if (!isBuiltIn) {
                        Text(
                            text = stringResource(R.string.category_delete_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onDeleteOrReset,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isBuiltIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.testTag("category_delete_or_reset"),
                    ) {
                        Text(stringResource(if (isBuiltIn) R.string.category_reset else R.string.category_delete))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, color, name.trim() != initialName) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("category_save"),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TagEditDialog(
    tag: Tag,
    onDismiss: () -> Unit,
    onRename: (name: String, onResult: (Boolean) -> Unit) -> Unit,
    onDelete: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(tag.name) }
    var duplicate by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        if (it.length <= Limits.TAG_NAME) {
                            name = it
                            duplicate = false
                        }
                    },
                    label = { Text(stringResource(R.string.tag_name_label)) },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = if (duplicate) {
                        { Text(stringResource(R.string.tag_exists)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("tag_name_input"),
                )
                Text(
                    text = stringResource(R.string.tag_delete_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("tag_delete"),
                ) {
                    Text(stringResource(R.string.tag_delete))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) { ok -> if (ok) onDismiss() else duplicate = true } },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("tag_save"),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
