package space.abhijithkm.shoppinglist.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.abhijithkm.shoppinglist.data.local.ListPrefsDao
import space.abhijithkm.shoppinglist.data.local.ListPrefsEntity
import space.abhijithkm.shoppinglist.data.local.ReminderDao
import space.abhijithkm.shoppinglist.data.local.ReminderEntity

/**
 * A list's reminder and "Shopping mode" switch. Both live only on this device (never synced). Writing here is enough:
 * the surface updater notices the change and schedules alarms, posts or cancels notifications and redraws widgets.
 */
@Singleton
class SurfacePrefsRepository @Inject constructor(
    private val reminders: ReminderDao,
    private val prefs: ListPrefsDao,
) {
    fun observeReminder(listId: String): Flow<ReminderEntity?> = reminders.observe(listId)

    suspend fun saveReminder(r: ReminderEntity) = reminders.upsert(r)

    /** Turns the reminder off but keeps its settings, so switching it on again restores them. */
    suspend fun disableReminder(listId: String) = reminders.disable(listId)

    fun observeShoppingMode(listId: String): Flow<Boolean> = prefs.observe(listId).map { it?.shoppingMode == true }

    suspend fun setShoppingMode(listId: String, on: Boolean) = prefs.upsert(ListPrefsEntity(listId, on))
}
