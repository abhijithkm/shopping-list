package space.abhijithkm.shoppinglist.feature.listdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.Item
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.core.model.Limits
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.core.util.formatQuantity
import space.abhijithkm.shoppinglist.ui.components.displayName

private val UnitSuggestions = listOf("kg", "g", "l", "ml", "pieces", "packets", "bottles", "dozen")

/**
 * Bottom sheet for everything an item can carry beyond its name: quantity, unit, category, tags and
 * notes. Quick-add stays inline on the list, so this sheet is only opened on demand.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemEditSheet(
    item: Item,
    categories: List<Category>,
    tags: List<Tag>,
    onDismiss: () -> Unit,
    onSave: (ItemUpdate) -> Unit,
    onDelete: () -> Unit,
    onCreateTag: (name: String, onCreated: (String) -> Unit) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(item.name) }
    var quantityText by rememberSaveable { mutableStateOf(item.quantity?.let(::formatQuantity).orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(item.unit.orEmpty()) }
    var categoryKey by rememberSaveable { mutableStateOf(item.categoryKey) }
    var notes by rememberSaveable { mutableStateOf(item.notes.orEmpty()) }
    var tagIds by rememberSaveable { mutableStateOf(item.tags.map { it.id }) }
    var newTag by rememberSaveable { mutableStateOf("") }

    val quantity = quantityText.trim().replace(',', '.').toDoubleOrNull()
    val quantityInvalid = quantityText.isNotBlank() &&
        (quantity == null || quantity <= 0.0 || quantity > Limits.MAX_QUANTITY)
    val canSave = name.isNotBlank() && !quantityInvalid

    val addTag = {
        val trimmed = newTag.trim()
        if (trimmed.isNotEmpty()) {
            onCreateTag(trimmed) { id -> if (id !in tagIds) tagIds = tagIds + id }
            newTag = ""
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The fields scroll; the Delete/Save row below stays pinned so it is never hidden by the keyboard.
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= Limits.ITEM_NAME) name = it },
                label = { Text(stringResource(R.string.item_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("edit_name"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.take(10) },
                    label = { Text(stringResource(R.string.item_quantity)) },
                    isError = quantityInvalid,
                    supportingText = if (quantityInvalid) {
                        { Text(stringResource(R.string.quantity_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("edit_quantity"),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { if (it.length <= Limits.UNIT) unit = it },
                    label = { Text(stringResource(R.string.item_unit)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("edit_unit"),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitSuggestions.forEach { suggestion ->
                    AssistChip(onClick = { unit = suggestion }, label = { Text(suggestion) })
                }
            }

            Text(stringResource(R.string.item_category), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = categoryKey == null,
                    onClick = { categoryKey = null },
                    label = { Text(stringResource(R.string.category_none)) },
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = categoryKey == category.key,
                        onClick = { categoryKey = category.key },
                        label = { Text(category.displayName()) },
                        leadingIcon = {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(category.color)))
                        },
                    )
                }
            }

            Text(stringResource(R.string.item_tags), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in tagIds,
                        onClick = { tagIds = if (tag.id in tagIds) tagIds - tag.id else tagIds + tag.id },
                        label = { Text(tag.name) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { if (it.length <= Limits.TAG_NAME) newTag = it },
                    label = { Text(stringResource(R.string.tag_new)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag() }),
                    modifier = Modifier.weight(1f).testTag("edit_new_tag"),
                )
                IconButton(onClick = { addTag() }, enabled = newTag.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tag_add))
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { if (it.length <= Limits.ITEM_NOTES) notes = it },
                label = { Text(stringResource(R.string.item_notes)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("edit_notes"),
            )

        }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.delete), modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = {
                        onSave(
                            ItemUpdate(
                                id = item.id,
                                name = name,
                                quantity = quantity?.takeIf { !quantityInvalid },
                                unit = unit.takeIf { it.isNotBlank() },
                                categoryKey = categoryKey,
                                notes = notes.takeIf { it.isNotBlank() },
                                tagIds = tagIds.toSet(),
                            ),
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.testTag("edit_save"),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}
