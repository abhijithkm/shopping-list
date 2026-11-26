package space.abhijithkm.shoppinglist.data.repository

import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** The two global notification switches from Settings, as a small seam so the code that posts notifications can be tested. */
interface NotificationPrefs {
    suspend fun remindersEnabled(): Boolean

    suspend fun shoppingModeEnabled(): Boolean
}

class SettingsNotificationPrefs @Inject constructor(
    private val settings: SettingsRepository,
) : NotificationPrefs {
    override suspend fun remindersEnabled(): Boolean = settings.settings.first().remindersEnabled

    override suspend fun shoppingModeEnabled(): Boolean = settings.settings.first().shoppingModeEnabled
}
