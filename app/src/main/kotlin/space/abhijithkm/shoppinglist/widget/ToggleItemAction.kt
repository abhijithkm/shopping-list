package space.abhijithkm.shoppinglist.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dagger.hilt.android.EntryPointAccessors

internal val ItemIdKey = ActionParameters.Key<String>("item_id")
internal val BoughtKey = ActionParameters.Key<Boolean>("bought")

/** A tick in the widget. It only writes to the local database (no network), then redraws this widget at once. */
class ToggleItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val itemId = parameters[ItemIdKey] ?: return
        val bought = parameters[BoughtKey] ?: return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        entry.repository().setItemBought(itemId, bought)
        ShoppingListWidget().update(context, glanceId)
    }
}
