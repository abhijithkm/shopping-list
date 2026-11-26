package space.abhijithkm.shoppinglist.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE listId = :listId")
    fun observe(listId: String): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders WHERE listId = :listId")
    suspend fun get(listId: String): ReminderEntity?

    @Upsert
    suspend fun upsert(r: ReminderEntity)

    @Query("DELETE FROM reminders WHERE listId = :listId")
    suspend fun delete(listId: String)

    @Query("SELECT * FROM reminders WHERE enabled = 1")
    suspend fun allEnabled(): List<ReminderEntity>

    /** Every reminder, disabled ones too: the scheduler must cancel the alarm of a reminder that was switched off. */
    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>

    @Query("UPDATE reminders SET lastFiredAt = :at WHERE listId = :listId")
    suspend fun markFired(listId: String, at: Long)

    @Query("UPDATE reminders SET enabled = 0 WHERE listId = :listId")
    suspend fun disable(listId: String)
}

@Dao
interface ListPrefsDao {
    @Query("SELECT * FROM list_prefs WHERE listId = :listId")
    fun observe(listId: String): Flow<ListPrefsEntity?>

    @Upsert
    suspend fun upsert(p: ListPrefsEntity)

    @Query("SELECT listId FROM list_prefs WHERE shoppingMode = 1")
    suspend fun shoppingModeListIds(): List<String>
}
