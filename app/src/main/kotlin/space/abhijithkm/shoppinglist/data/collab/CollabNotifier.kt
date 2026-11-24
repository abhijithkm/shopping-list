package space.abhijithkm.shoppinglist.data.collab

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import space.abhijithkm.shoppinglist.MainActivity
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.repository.SettingsRepository
import space.abhijithkm.shoppinglist.data.sync.SyncObserver
import space.abhijithkm.shoppinglist.data.sync.SyncRunResult

/**
 * Local notifications that follow what a sync found (docs/APPHUB_INTEGRATION.md, "Notifications"): AppHub has no push,
 * so an invitation, a join request, a removal or a change by another member is announced when the next sync reads it,
 * which is at most the sync interval later while the app is closed. Nothing here talks to a server.
 *
 * An announcement is marked as done in the database only after it was posted, so a missing notification permission
 * delays it rather than losing it. Texts name a list but never carry an email address, a code or an item's content.
 */
@Singleton
class CollabNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : SyncObserver {
    private val collab = db.collabDao()

    override suspend fun onRunFinished(account: String, result: SyncRunResult) {
        val prefs = settings.settings.first()
        if (prefs.notifyInvitations) {
            announceInvitations(account)
            announceRequests(account)
            announceRemovals(account)
        }
        if (prefs.notifySharedActivity) announceActivity(result)
    }

    private suspend fun announceInvitations(account: String) {
        val rows = collab.invitationsToAnnounce(account, clock.nowMillis())
        if (rows.isEmpty()) return
        val posted = rows.filter { row ->
            post(
                id = NOTIFICATION_BASE + row.id.hashCode(),
                channel = CHANNEL_INVITATIONS,
                title = context.getString(R.string.notify_invitation_title),
                text = context.getString(R.string.notify_invitation_text, row.inviterName ?: context.getString(R.string.someone), row.listName ?: context.getString(R.string.a_list)),
            )
        }
        if (posted.isNotEmpty()) collab.markInvitationsNotified(posted.map { it.id })
    }

    private suspend fun announceRequests(account: String) {
        val rows = collab.requestsToAnnounce(account)
        if (rows.isEmpty()) return
        val posted = rows.filter { row ->
            post(
                id = NOTIFICATION_BASE + row.id.hashCode(),
                channel = CHANNEL_INVITATIONS,
                title = context.getString(R.string.notify_request_title),
                text = context.getString(R.string.notify_request_text, row.requesterName ?: context.getString(R.string.someone), row.listName ?: context.getString(R.string.a_list)),
            )
        }
        if (posted.isNotEmpty()) collab.markRequestsNotified(posted.map { it.id })
    }

    private suspend fun announceRemovals(account: String) {
        val rows = collab.removalsToAnnounce(account)
        if (rows.isEmpty()) return
        val posted = rows.filter { row ->
            // The list is already gone from this device, so its name is not known any more: the text says so plainly.
            post(
                id = NOTIFICATION_BASE + row.id.hashCode(),
                channel = CHANNEL_INVITATIONS,
                title = context.getString(R.string.notify_removed_title),
                text = context.getString(if (row.removal == "left") R.string.notify_left_text else R.string.notify_removed_text),
            )
        }
        if (posted.isNotEmpty()) collab.markMembersNotified(posted.map { it.id })
    }

    private suspend fun announceActivity(result: SyncRunResult) {
        for ((listId, count) in result.sharedChanges) {
            val name = db.listDao().getById(listId)?.name ?: continue
            post(
                id = ACTIVITY_BASE + listId.hashCode(),
                channel = CHANNEL_ACTIVITY,
                title = name,
                text = context.resources.getQuantityString(R.plurals.notify_activity_text, count, count),
                openListId = listId,
            )
        }
    }

    /** Posts one notification; false when notifications are not allowed (nothing is marked as announced then). */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, channel: String, title: String, text: String, openListId: String? = null): Boolean {
        ensureChannels(context)
        if (!canPost()) return false
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openListId != null) putExtra(EXTRA_OPEN_LIST, openListId) else putExtra(EXTRA_OPEN_INBOX, true)
        }
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        const val CHANNEL_INVITATIONS = "collab_invitations"
        const val CHANNEL_ACTIVITY = "shared_activity"
        const val EXTRA_OPEN_LIST = "open_list"
        const val EXTRA_OPEN_INBOX = "open_inbox"
        private const val NOTIFICATION_BASE = 10_000
        private const val ACTIVITY_BASE = 5_000_000

        /** Creates the channels (API 26+); safe to call again. The user can silence a channel in system settings. */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_INVITATIONS, context.getString(R.string.channel_invitations), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_invitations_desc) },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ACTIVITY, context.getString(R.string.channel_activity), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_activity_desc) },
            )
        }
    }
}
