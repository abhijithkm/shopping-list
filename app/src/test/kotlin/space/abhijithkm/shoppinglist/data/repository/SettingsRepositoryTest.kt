package space.abhijithkm.shoppinglist.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.abhijithkm.shoppinglist.core.model.ListSort
import space.abhijithkm.shoppinglist.core.model.ThemeMode

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }

    @Test
    fun `defaults when nothing is stored`() = runTest {
        val repository = SettingsRepository(newStore())
        val settings = repository.settings.first()
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertTrue(settings.showCompleted)
    }

    @Test
    fun `theme mode is persisted`() = runTest {
        val repository = SettingsRepository(newStore())
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.settings.first().themeMode)
    }

    @Test
    fun `show completed is persisted`() = runTest {
        val repository = SettingsRepository(newStore())
        repository.setShowCompleted(false)
        assertFalse(repository.settings.first().showCompleted)
    }

    @Test
    fun `an unknown stored theme falls back to system`() = runTest {
        val store = newStore()
        store.edit { it[stringPreferencesKey("theme_mode")] = "PURPLE" }
        assertEquals(ThemeMode.SYSTEM, SettingsRepository(store).settings.first().themeMode)
    }

    @Test
    fun `list sort and grouping default to recent and ungrouped`() = runTest {
        val settings = SettingsRepository(newStore()).settings.first()
        assertEquals(ListSort.RECENT, settings.listSort)
        assertFalse(settings.groupByCategory)
    }

    // Each persistence test performs exactly one write: on Windows DataStore's atomic rename fails
    // when it must replace a file it still holds open (a JVM test-environment quirk only).
    @Test
    fun `list sort is persisted`() = runTest {
        val repository = SettingsRepository(newStore())
        repository.setListSort(ListSort.NAME)
        assertEquals(ListSort.NAME, repository.settings.first().listSort)
    }

    @Test
    fun `grouping by category is persisted`() = runTest {
        val repository = SettingsRepository(newStore())
        repository.setGroupByCategory(true)
        assertTrue(repository.settings.first().groupByCategory)
    }
}
