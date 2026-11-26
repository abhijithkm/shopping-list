package space.abhijithkm.shoppinglist.surfaces

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.R

/** Posts and cancels the app's own notifications. Nothing here touches the network or the database. */
@Singleton
class SurfaceNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    /** False when the notification permission is denied (Android 13+) or notifications are switched off for the app. */
    fun canNotify(): Boolean = manager.areNotificationsEnabled()

    /** The notification id of a list's shopping-mode notification (the tag tells it apart from a reminder of the same list). */
    fun notificationId(listId: String): Int = listId.hashCode()

    /** Posts or updates the ongoing notification of one list. Silent: only the first post may draw attention, and the channel is low importance. */
    @SuppressLint("MissingPermission") // canNotify() checks the permission
    fun postShoppingMode(content: ShoppingModeContent) {
        if (!canNotify()) return
        val id = notificationId(content.listId)
        val open = PendingIntent.getActivity(
            context,
            id,
            openListIntent(context, content.listId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val markAll = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, MarkAllDoneReceiver::class.java).putExtra(MarkAllDoneReceiver.EXTRA_LIST_ID, content.listId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle()
        content.nextItems.forEach { style.addLine(it) }
        val more = content.openCount - content.nextItems.size
        if (more > 0) style.setSummaryText(context.getString(R.string.shopping_mode_more, more))
        val notification = NotificationCompat.Builder(context, NotificationChannels.SHOPPING_MODE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.shopping_mode_title, content.listName, content.openCount, content.totalCount))
            .setContentText(content.nextItems.joinToString(", "))
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.open_list), open)
            .addAction(0, context.getString(R.string.mark_all_done), markAll)
            .build()
        manager.notify(TAG_SHOPPING_MODE, id, notification)
    }

    /** Cancels every shopping-mode notification except those of [wantedListIds] (a deleted, archived or finished list loses it). */
    fun cancelShoppingModeExcept(wantedListIds: Set<String>) {
        val wantedIds = wantedListIds.map { notificationId(it) }.toSet()
        manager.activeNotifications
            .filter { it.tag == TAG_SHOPPING_MODE && it.id !in wantedIds }
            .forEach { manager.cancel(TAG_SHOPPING_MODE, it.id) }
    }

    /** A reminder: the list name and how many items are left. Tapping it opens the list and removes it. */
    @SuppressLint("MissingPermission") // canNotify() checks the permission
    fun postReminder(listId: String, listName: String, openCount: Int) {
        if (!canNotify()) return
        manager.notify(TAG_REMINDER, notificationId(listId), buildReminder(listId, listName, openCount))
    }

    /** The reminder notification as built, apart from posting it (the system rewrites some fields, e.g. `defaults`, when it accepts one). */
    internal fun buildReminder(listId: String, listName: String, openCount: Int): Notification {
        val id = notificationId(listId)
        val open = PendingIntent.getActivity(
            context,
            id,
            openListIntent(context, listId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(listName)
            .setContentText(context.resources.getQuantityString(R.plurals.reminder_items_left, openCount, openCount))
            // Android 7 and 7.1 have no channels (minSdk is 24): without defaults the reminder would arrive silent. Ignored from Android 8.
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val TAG_SHOPPING_MODE = "shopping_mode"
        const val TAG_REMINDER = "reminder"
    }
}
