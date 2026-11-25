package space.abhijithkm.shoppinglist.surfaces

import androidx.room.InvalidationTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.data.local.AppDatabase

/** Redraws everything that shows list data (widgets, shopping-mode notifications) and keeps reminder alarms in step. */
interface SurfaceRefresher {
    suspend fun refreshAll()
}

/** Tells when local data changed. A seam so the debounce can be tested without Room. */
fun interface ChangeSource {
    fun changes(): Flow<Unit>
}

/**
 * Listens to Room's own change tracking for the tables the surfaces show, so a change from the app, a widget tap, the
 * sync worker or a notification button is caught no matter who wrote it. Room announces the current state once when
 * the collection starts, which draws every surface at app start.
 */
class RoomChangeSource @Inject constructor(private val db: AppDatabase) : ChangeSource {
    override fun changes(): Flow<Unit> {
        val tracker: InvalidationTracker = db.invalidationTracker
        return tracker.createFlow("lists", "items", "reminders", "list_prefs").map { }
    }
}

/** Merges bursts of changes (for example "Mark all done") into one refresh after [DEFAULT_DEBOUNCE_MS]. */
@Singleton
class SurfaceUpdater @Inject constructor(
    private val source: ChangeSource,
    private val refresher: SurfaceRefresher,
) {
    /** A failed refresh is passed to [onError] and the collection goes on: one bad refresh must not stop the next. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, debounceMs: Long = DEFAULT_DEBOUNCE_MS, onError: (Throwable) -> Unit = {}): Job =
        scope.launch {
            source.changes().debounce(debounceMs).collect {
                try {
                    refresher.refreshAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
    }
}
