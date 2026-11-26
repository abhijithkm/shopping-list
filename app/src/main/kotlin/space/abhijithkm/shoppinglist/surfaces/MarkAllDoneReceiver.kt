package space.abhijithkm.shoppinglist.surfaces

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository

/** The "Mark all done" button of the shopping-mode notification. The notification then updates itself through the surface updater. */
@AndroidEntryPoint
class MarkAllDoneReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: LocalShoppingRepository

    override fun onReceive(context: Context, intent: Intent) {
        val listId = intent.getStringExtra(EXTRA_LIST_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.markAllBought(listId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_LIST_ID = "list_id"
    }
}
