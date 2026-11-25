package space.abhijithkm.shoppinglist.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.currentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.surfaces.openListIntent

/**
 * The home-screen widget: one list per widget instance, redrawn whenever local data changes (the surface updater calls
 * updateAll after a short debounce; there is no periodic refresh). It reads only the local database.
 */
class ShoppingListWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // The first frame is ready before the composition starts, so the widget never flashes empty.
        val firstListId = boundListId(context, id)
        val firstModel = withContext(Dispatchers.IO) {
            if (firstListId == null) {
                WidgetModel.NotConfigured
            } else {
                buildWidgetModel(entry.lists().getById(firstListId), entry.items().allOfList(firstListId))
            }
        }
        provideContent {
            // Everything that can change is read INSIDE the composition: Glance keeps it alive for a while after the first
            // draw and update() only recomposes it, so a list chosen later, a tick or an edit would never show if the model
            // was computed once out here (found on a real launcher: a widget that kept saying "tap to choose a list").
            val listId = currentState<Preferences>()[LIST_ID_KEY]
            val model by remember(listId) { modelFlow(entry, listId) }
                .collectAsState(initial = if (listId == firstListId) firstModel else WidgetModel.NotConfigured)
            GlanceTheme { WidgetBody(context, model, appWidgetId) }
        }
    }
}

private fun modelFlow(entry: WidgetEntryPoint, listId: String?): Flow<WidgetModel> =
    if (listId == null) {
        flowOf(WidgetModel.NotConfigured)
    } else {
        widgetModels(
            list = entry.lists().observeOneWithCounts(listId).map { it?.list },
            items = entry.items().observeForList(listId).map { rows -> rows.map { it.item } },
        )
    }

@Composable
private fun WidgetBody(context: Context, model: WidgetModel, appWidgetId: Int) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(12.dp),
    ) {
        when (model) {
            is WidgetModel.Content -> ContentBody(context, model)
            WidgetModel.Unavailable -> Message(context, context.getString(R.string.widget_not_available), appWidgetId)
            WidgetModel.NotConfigured -> Message(context, context.getString(R.string.widget_not_configured), appWidgetId)
        }
    }
}

/** An unavailable or unconfigured widget: a tap opens the list chooser for this very widget. */
@Composable
private fun Message(context: Context, text: String, appWidgetId: Int) {
    val chooser = Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(chooser)),
    )
}

@Composable
private fun ContentBody(context: Context, model: WidgetModel.Content) {
    val progress = when {
        model.totalCount == 0 -> context.getString(R.string.widget_no_items)
        model.openCount == 0 -> context.getString(R.string.widget_all_done)
        else -> context.getString(R.string.widget_progress, model.openCount, model.totalCount)
    }
    val quickAdd = Intent(context, QuickAddActivity::class.java).putExtra(QuickAddActivity.EXTRA_LIST_ID, model.listId)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(openListIntent(context, model.listId))),
        ) {
            Text(
                text = model.listName,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                text = progress,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
        Image(
            provider = ImageProvider(R.drawable.ic_widget_add),
            contentDescription = context.getString(R.string.widget_add),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(36.dp).padding(6.dp).clickable(actionStartActivity(quickAdd)),
        )
    }
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        items(model.rows, itemId = { it.itemId.hashCode().toLong() }) { row ->
            CheckBox(
                checked = row.bought,
                onCheckedChange = actionRunCallback<ToggleItemAction>(
                    actionParametersOf(ItemIdKey to row.itemId, BoughtKey to !row.bought),
                ),
                text = row.label,
                maxLines = 1,
                style = TextStyle(
                    color = if (row.bought) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    textDecoration = if (row.bought) TextDecoration.LineThrough else TextDecoration.None,
                ),
            )
        }
    }
}
