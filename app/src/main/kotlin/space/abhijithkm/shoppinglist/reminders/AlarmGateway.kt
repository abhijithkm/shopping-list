package space.abhijithkm.shoppinglist.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Sets and removes one alarm per list. A seam so the scheduling rules can be tested without AlarmManager. */
interface AlarmGateway {
    fun schedule(listId: String, atMillis: Long)

    fun cancel(listId: String)
}

/**
 * Inexact alarms only (`setAndAllowWhileIdle`): they may fire a few minutes late, and need no exact-alarm permission,
 * which Play restricts to alarm and calendar apps. Each list's alarm has its own data URI, so two lists can never share one.
 */
@Singleton
class AlarmManagerGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmGateway {
    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun schedule(listId: String, atMillis: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            alarmIntent(context, listId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    override fun cancel(listId: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            alarmIntent(context, listId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    companion object {
        /** The intent an alarm delivers; its data URI makes the alarm of one list different from another's. */
        fun alarmIntent(context: Context, listId: String): Intent =
            Intent(context, ReminderReceiver::class.java)
                .setData(Uri.parse("shoppinglist://reminder/${Uri.encode(listId)}"))
                .putExtra(ReminderReceiver.EXTRA_LIST_ID, listId)
    }
}
