package space.abhijithkm.shoppinglist.feature.categories

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.DefaultCategory
import space.abhijithkm.shoppinglist.ui.NoInsets

@StringRes
internal fun DefaultCategory.labelRes(): Int = when (this) {
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

/** Phase 1 shows the built-in categories read-only. Custom categories, rename and colour come in Phase 2. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_categories)) }) },
        contentWindowInsets = NoInsets,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.categories_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(DefaultCategory.entries, key = { it.key }) { category ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(category.colorArgb)))
                    Text(
                        text = stringResource(category.labelRes()),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}
