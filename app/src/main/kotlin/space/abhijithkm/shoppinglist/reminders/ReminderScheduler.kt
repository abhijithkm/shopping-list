package space.abhijithkm.shoppinglist.reminders

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.local.ItemDao
import space.abhijithkm.shoppinglist.data.local.ReminderDao
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao
import space.abhijithkm.shoppinglist.data.repository.NotificationPrefs
import space.abhijithkm.shoppinglist.surfaces.SurfaceNotifier

/** The time zone reminders are computed in; the device's zone, replaceable in tests. */
fun interface ZoneProvider {
    fun zone(): ZoneId
}

/** What happens when a reminder's alarm fires. */
sealed interface ReminderDecision {
    data class Post(val listName: String, val openCount: Int) : ReminderDecision

    data object Skip : ReminderDecision
}

/**
 * Keeps the alarms in step with the reminder rows and handles an alarm firing. Alarms are rebuilt from Room whenever
 * data changes (the surface updater calls [rescheduleAll]) and after boot, time-zone change, clock change and app update.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    private val reminders: ReminderDao,
    private val lists: ShoppingListDao,
    private val items: ItemDao,
    private val gateway: AlarmGateway,
    private val clock: Clock,
    private val zones: ZoneProvider,
    private val notifier: SurfaceNotifier,
    private val prefs: NotificationPrefs,
) {
    /**
     * One alarm per enabled reminder of a live list, none for anything else (disabled reminder, deleted or archived list,
     * nothing left to fire). With [catchUp] (after boot only) a reminder missed while the phone was off fires once if it
     * was at most [ReminderMath.GRACE_MS] late and has not fired since.
     */
    suspend fun rescheduleAll(catchUp: Boolean) {
        val now = clock.nowMillis()
        val zone = zones.zone()
        for (reminder in reminders.all()) {
            val listId = reminder.listId
            val list = lists.getById(listId)
            if (!reminder.enabled || list == null || list.deletedAt != null || list.isArchived) {
                gateway.cancel(listId)
                continue
            }
            if (catchUp) {
                val missed = ReminderMath.lastOccurrenceAtOrBefore(now, zone, reminder)
                val alreadyFired = reminder.lastFiredAt?.let { missed != null && missed <= it } ?: false
                if (missed != null && now - missed <= ReminderMath.GRACE_MS && !alreadyFired) fire(listId)
            }
            val current = reminders.get(listId) ?: continue
            val next = if (current.enabled) nextAlarm(now, zone, current) else null
            if (next == null) gateway.cancel(listId) else gateway.schedule(listId, next)
        }
    }

    /**
     * The time of the next alarm. A reminder that is due but has not fired yet (an inexact alarm can be late) is re-armed at
     * once: without this, any change to the data at 09:00:10 would replace the pending 09:00 alarm with tomorrow's and the
     * reminder would be lost. A reminder saved after its time carries a baseline in `lastFiredAt`, so it is never "due".
     */
    private fun nextAlarm(now: Long, zone: ZoneId, r: ReminderEntity): Long? {
        val lastDue = ReminderMath.lastOccurrenceAtOrBefore(now, zone, r)
        val due = lastDue != null && now - lastDue <= DUE_WINDOW_MS && (r.lastFiredAt ?: Long.MIN_VALUE) < lastDue
        return if (due) now + DUE_REARM_DELAY_MS else ReminderMath.nextFire(now, zone, r)
    }

    companion object {
        /** An occurrence that passed less than this long ago, unfired, is still delivered (a late alarm); older ones are only caught up at boot. */
        const val DUE_WINDOW_MS = 10L * 60 * 1000

        /** How soon a due, unfired reminder is re-armed. */
        const val DUE_REARM_DELAY_MS = 1_000L
    }

    /** An alarm went off (or a missed one is caught up): decide, then post the notification if it is allowed. */
    suspend fun fire(listId: String) {
        val decision = onFired(listId)
        if (decision is ReminderDecision.Post && prefs.remindersEnabled() && notifier.canNotify()) {
            notifier.postReminder(listId, decision.listName, decision.openCount)
        }
    }

    /**
     * Records the fire, disables a one-time reminder, schedules the next occurrence of a repeating one and says whether
     * a notification is due: not for a deleted or archived list, nor when nothing is left and the reminder only wants to
     * speak up when items remain.
     */
    suspend fun onFired(listId: String): ReminderDecision {
        val reminder = reminders.get(listId) ?: return ReminderDecision.Skip
        if (!reminder.enabled) return ReminderDecision.Skip
        val now = clock.nowMillis()
        reminders.markFired(listId, now)
        if (reminder.repeat == ReminderRepeat.NONE) reminders.disable(listId)
        val next = if (reminder.repeat == ReminderRepeat.NONE) null else ReminderMath.nextFire(now, zones.zone(), reminder)
        if (next == null) gateway.cancel(listId) else gateway.schedule(listId, next)

        val list = lists.getById(listId)
        if (list == null || list.deletedAt != null || list.isArchived) return ReminderDecision.Skip
        val open = items.openItemsInOrder(listId).size
        if (reminder.onlyIfItemsRemain && open == 0) return ReminderDecision.Skip
        return ReminderDecision.Post(list.name, open)
    }
}
