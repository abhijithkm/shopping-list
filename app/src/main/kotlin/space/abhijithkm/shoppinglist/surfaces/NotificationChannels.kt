package space.abhijithkm.shoppinglist.surfaces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import space.abhijithkm.shoppinglist.R

/** The two notification channels. Creating them again is harmless, so the app does it at every start. */
object NotificationChannels {
    /** Timed reminders: a normal notification the owner chose to receive. */
    const val REMINDERS = "reminders"

    /** The ongoing "Shopping mode" notification: low importance, so it never makes a sound or pops up. */
    const val SHOPPING_MODE = "shopping_mode"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(SHOPPING_MODE, context.getString(R.string.channel_shopping_mode), NotificationManager.IMPORTANCE_LOW),
        )
    }
}
