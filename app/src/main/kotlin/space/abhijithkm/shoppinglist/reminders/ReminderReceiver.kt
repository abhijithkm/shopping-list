package space.abhijithkm.shoppinglist.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives a list's reminder alarm. Not exported: only the app's own PendingIntent reaches it. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val listId = intent.getStringExtra(EXTRA_LIST_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.fire(listId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_LIST_ID = "list_id"
    }
}
