package space.abhijithkm.shoppinglist.surfaces

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.reminders.ReminderScheduler
import space.abhijithkm.shoppinglist.widget.ShoppingListWidget

/** Redraws the widgets, brings the shopping-mode notifications in line with the data and rebuilds the reminder alarms. */
@Singleton
class DefaultSurfaceRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shoppingMode: ShoppingModeRefresher,
    private val reminderScheduler: ReminderScheduler,
) : SurfaceRefresher {
    override suspend fun refreshAll() {
        ShoppingListWidget().updateAll(context)
        shoppingMode.refresh()
        reminderScheduler.rescheduleAll(catchUp = false)
    }
}
