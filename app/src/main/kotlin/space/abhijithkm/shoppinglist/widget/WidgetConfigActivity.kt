package space.abhijithkm.shoppinglist.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

/**
 * Shown when a widget is added (and from "Reconfigure" or a tap on an unavailable widget): the owner picks the list this
 * widget instance shows. Backing out cancels the widget (RESULT_CANCELED is the default result).
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {
    @Inject lateinit var repository: LocalShoppingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            ShoppingListTheme {
                val lists by repository.observeLists(archived = false).collectAsStateWithLifecycle(initialValue = emptyList())
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(top = 24.dp)) {
                        Text(
                            stringResource(R.string.widget_config_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        if (lists.isEmpty()) {
                            Text(
                                stringResource(R.string.widget_config_empty),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        LazyColumn {
                            items(lists, key = { it.id }) { list ->
                                ListItem(
                                    headlineContent = { Text(list.name) },
                                    supportingContent = { Text(stringResource(R.string.widget_progress, list.remainingCount, list.totalCount)) },
                                    modifier = Modifier.clickable { choose(appWidgetId, list.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(appWidgetId: Int, listId: String) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            bindWidget(this@WidgetConfigActivity, glanceId, listId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}
