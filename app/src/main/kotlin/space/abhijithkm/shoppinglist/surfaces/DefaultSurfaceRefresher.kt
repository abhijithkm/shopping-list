package space.abhijithkm.shoppinglist.surfaces

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.data.local.ItemDao
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao
import space.abhijithkm.shoppinglist.widget.ShoppingListWidget

/** Redraws the widgets and brings the shopping-mode notifications in line with the data. Reminder alarms join in a later task. */
@Singleton
class DefaultSurfaceRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lists: ShoppingListDao,
    private val items: ItemDao,
    private val notifier: SurfaceNotifier,
) : SurfaceRefresher {
    override suspend fun refreshAll() {
        ShoppingListWidget().updateAll(context)
        refreshShoppingMode()
    }

    private suspend fun refreshShoppingMode() {
        val wanted = mutableSetOf<String>()
        if (notifier.canNotify()) {
            for (list in lists.shoppingModeLists()) {
                val content = shoppingModeContent(list, items.allOfList(list.id)) ?: continue
                notifier.postShoppingMode(content)
                wanted += list.id
            }
        }
        notifier.cancelShoppingModeExcept(wanted)
    }
}
