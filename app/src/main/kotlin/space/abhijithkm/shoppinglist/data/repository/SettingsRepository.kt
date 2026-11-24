package space.abhijithkm.shoppinglist.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.ListSort
import space.abhijithkm.shoppinglist.core.model.ThemeMode

/** App preferences in DataStore (theme, list sort, completed-items and grouping options). Secrets never live here; tokens get their own encrypted store in Phase 3. */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs.toSettings() }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setShowCompleted(show: Boolean) {
        dataStore.edit { it[SHOW_COMPLETED] = show }
    }

    suspend fun setListSort(sort: ListSort) {
        dataStore.edit { it[LIST_SORT] = sort.name }
    }

    suspend fun setGroupByCategory(group: Boolean) {
        dataStore.edit { it[GROUP_BY_CATEGORY] = group }
    }

    suspend fun setNotifyInvitations(enabled: Boolean) {
        dataStore.edit { it[NOTIFY_INVITATIONS] = enabled }
    }

    suspend fun setNotifySharedActivity(enabled: Boolean) {
        dataStore.edit { it[NOTIFY_SHARED_ACTIVITY] = enabled }
    }

    private fun Preferences.toSettings() = AppSettings(
        themeMode = this[THEME_MODE]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM,
        showCompleted = this[SHOW_COMPLETED] ?: true,
        listSort = this[LIST_SORT]?.let { stored -> ListSort.entries.firstOrNull { it.name == stored } }
            ?: ListSort.RECENT,
        groupByCategory = this[GROUP_BY_CATEGORY] ?: false,
        notifyInvitations = this[NOTIFY_INVITATIONS] ?: true,
        notifySharedActivity = this[NOTIFY_SHARED_ACTIVITY] ?: true,
    )

    private companion object {
        val NOTIFY_INVITATIONS = booleanPreferencesKey("notify_invitations")
        val NOTIFY_SHARED_ACTIVITY = booleanPreferencesKey("notify_shared_activity")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHOW_COMPLETED = booleanPreferencesKey("show_completed")
        val LIST_SORT = stringPreferencesKey("list_sort")
        val GROUP_BY_CATEGORY = booleanPreferencesKey("group_by_category")
    }
}
