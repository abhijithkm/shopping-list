package space.abhijithkm.shoppinglist.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.DefaultCategory

@StringRes
fun DefaultCategory.labelRes(): Int = when (this) {
    DefaultCategory.VEGETABLES -> R.string.category_vegetables
    DefaultCategory.FRUITS -> R.string.category_fruits
    DefaultCategory.DAIRY -> R.string.category_dairy
    DefaultCategory.MEAT_FISH -> R.string.category_meat_fish
    DefaultCategory.BAKERY -> R.string.category_bakery
    DefaultCategory.GROCERIES -> R.string.category_groceries
    DefaultCategory.BEVERAGES -> R.string.category_beverages
    DefaultCategory.HOUSEHOLD -> R.string.category_household
    DefaultCategory.PERSONAL_CARE -> R.string.category_personal_care
    DefaultCategory.MEDICINES -> R.string.category_medicines
    DefaultCategory.OTHERS -> R.string.category_others
}

/** The user's own name for the category, or the built-in label. */
@Composable
fun Category.displayName(): String = name ?: defaultCategory?.let { stringResource(it.labelRes()) }.orEmpty()

/** Colours offered for lists and custom categories (ARGB). */
val PickerColors: List<Int> = listOf(
    0xFF1F6B4F.toInt(),
    0xFF1E88E5.toInt(),
    0xFFE53935.toInt(),
    0xFFFB8C00.toInt(),
    0xFF8E24AA.toInt(),
    0xFF00ACC1.toInt(),
    0xFF6D4C41.toInt(),
    0xFF546E7A.toInt(),
)

/** Optional list icons: a single emoji each, so they need no assets and work offline. */
val PickerIcons: List<String> = listOf("🛒", "🥦", "🍎", "🥛", "🍞", "🧴", "💊", "🏠", "🎉", "🎁")

/** A single-choice row of colour swatches with a leading "no colour" choice. */
@Composable
fun ColorPicker(
    selected: Int?,
    onSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    options: List<Int> = PickerColors,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Swatch(color = null, selected = selected == null, description = stringResource(R.string.color_none)) {
            onSelected(null)
        }
        options.forEachIndexed { index, color ->
            Swatch(
                color = color,
                selected = selected == color,
                description = stringResource(R.string.color_option, index + 1),
            ) { onSelected(color) }
        }
    }
}

@Composable
private fun Swatch(color: Int?, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
        Box(
            modifier = Modifier
                .size(36.dp)
                .border(3.dp, ring, CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (color == null) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A single-choice row of emoji icons with a leading "no icon" choice. */
@Composable
fun IconPicker(
    selected: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    options: List<String> = PickerIcons,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconChoice(emoji = null, selected = selected == null, description = stringResource(R.string.icon_none)) {
            onSelected(null)
        }
        options.forEach { emoji ->
            IconChoice(
                emoji = emoji,
                selected = selected == emoji,
                description = stringResource(R.string.icon_option, emoji),
            ) { onSelected(emoji) }
        }
    }
}

@Composable
private fun IconChoice(emoji: String?, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (emoji == null) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(emoji, fontSize = 22.sp)
        }
    }
}
