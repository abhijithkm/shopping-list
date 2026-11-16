package space.abhijithkm.shoppinglist

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository

@HiltAndroidApp
class ShoppingListApp : Application() {
    @Inject lateinit var repository: LocalShoppingRepository

    @Inject lateinit var clock: Clock

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Deleted lists, items, categories and tags stay recoverable for a week (undo), then go for good.
        appScope.launch {
            // Housekeeping is non-critical: a failure here must never take the app down at start.
            runCatching { repository.purgeExpiredTombstones(clock.nowMillis() - TOMBSTONE_RETENTION_MS) }
                .onFailure { Log.w(TAG, "Tombstone cleanup failed", it) }
        }
    }

    private companion object {
        const val TAG = "ShoppingListApp"
        const val TOMBSTONE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
