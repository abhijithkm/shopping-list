package space.abhijithkm.shoppinglist.surfaces

import javax.inject.Inject
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.data.local.ItemDao
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao
import space.abhijithkm.shoppinglist.data.repository.NotificationPrefs

/**
 * Brings the ongoing "Shopping mode" notifications in line with the data: one for each list whose switch is on and that
 * still has something to buy, none for anything else. With the global switch off or the notification permission denied
 * every existing one is cancelled.
 */
@Singleton
class ShoppingModeRefresher @Inject constructor(
    private val lists: ShoppingListDao,
    private val items: ItemDao,
    private val notifier: SurfaceNotifier,
    private val prefs: NotificationPrefs,
) {
    suspend fun refresh() {
        val wanted = mutableSetOf<String>()
        if (prefs.shoppingModeEnabled() && notifier.canNotify()) {
            for (list in lists.shoppingModeLists()) {
                val content = shoppingModeContent(list, items.allOfList(list.id)) ?: continue
                notifier.postShoppingMode(content)
                wanted += list.id
            }
        }
        notifier.cancelShoppingModeExcept(wanted)
    }
}
