package space.abhijithkm.shoppinglist.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

/** The list a widget instance shows lives in that instance's own Glance state, so several widgets can show different lists. */
internal val LIST_ID_KEY = stringPreferencesKey("list_id")

suspend fun bindWidget(context: Context, glanceId: GlanceId, listId: String) {
    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs: Preferences ->
        prefs.toMutablePreferences().apply { this[LIST_ID_KEY] = listId }
    }
    ShoppingListWidget().update(context, glanceId)
}

/** The list chosen for this widget instance, or null when none was chosen yet. */
suspend fun boundListId(context: Context, glanceId: GlanceId): String? =
    getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[LIST_ID_KEY]
