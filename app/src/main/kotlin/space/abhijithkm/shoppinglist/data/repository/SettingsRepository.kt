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
import space.abhijithkm.shoppinglist.core.model.ThemeMode

/** App preferences in DataStore. Secrets never live here; tokens get their own encrypted store in Phase 3. */
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

    private fun Preferences.toSettings() = AppSettings(
        themeMode = this[THEME_MODE]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM,
        showCompleted = this[SHOW_COMPLETED] ?: true,
    )

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHOW_COMPLETED = booleanPreferencesKey("show_completed")
    }
}
