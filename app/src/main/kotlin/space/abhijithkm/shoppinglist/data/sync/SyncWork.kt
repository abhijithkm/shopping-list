package space.abhijithkm.shoppinglist.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.session.SessionManager
import space.abhijithkm.shoppinglist.data.session.SessionState

/** Schedules background sync. WorkManager survives process death and waits for a network connection. */
interface SyncScheduler {
    /** Makes sure a network-constrained run is queued (one at a time; extra requests are ignored). */
    fun requestSync(trigger: SyncTrigger)

    /** The slow safety net: a run every 30 minutes while signed in, so nothing waits long for the app to be opened. */
    fun ensurePeriodic()

    fun cancelAll()
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {
    private val workManager get() = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestSync(trigger: SyncTrigger) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger.name))
            .build()
        // KEEP: if a run is already queued (or retrying with backoff) another one adds nothing.
        workManager.enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncTrigger.PERIODIC.name))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(ONE_TIME_NAME)
        workManager.cancelUniqueWork(PERIODIC_NAME)
    }

    private companion object {
        const val ONE_TIME_NAME = "sync-once"
        const val PERIODIC_NAME = "sync-periodic"
        const val PERIOD_MINUTES = 30L
        const val BACKOFF_SECONDS = 30L
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.PERIODIC
        return try {
            // Offline, rate limited, server down or out of time: WorkManager retries with exponential backoff.
            if (engine.run(trigger).shouldRetry) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync run failed unexpectedly", e)
            if (runAttemptCount < MAX_UNEXPECTED_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "SyncWorker"
        private const val MAX_UNEXPECTED_ATTEMPTS = 5
    }
}

/**
 * Starts and stops syncing as the session changes: signed in means a first run now, a run shortly after
 * every local change, and the periodic safety net; anything else cancels the background work. Local edits
 * need no hook in the repository: the dirty-row count changes, and that is enough.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val session: SessionManager,
    private val db: AppDatabase,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
) {
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            session.restore()
            session.state
                .map { (it as? SessionState.SignedIn)?.account?.userId }
                .distinctUntilChanged()
                .collectLatest { account ->
                    if (account == null) {
                        scheduler.cancelAll()
                        return@collectLatest
                    }
                    scheduler.ensurePeriodic()
                    runAndRetry(SyncTrigger.APP_START)
                    db.syncDao().observeDirtyCount(account)
                        .distinctUntilChanged()
                        .filter { it > 0 }
                        .debounce(LOCAL_CHANGE_DEBOUNCE_MS)
                        // collect, not collectLatest: a run in progress finishes; changes made meanwhile raise the count again.
                        .collect { runAndRetry(SyncTrigger.LOCAL_CHANGE) }
                }
        }
    }

    private suspend fun runAndRetry(trigger: SyncTrigger) {
        var result = engine.run(trigger)
        if (result.outcome == SyncOutcome.COMPLETED && result.deferred > 0) {
            // Deletes still inside their undo window were left for later; come back once it is over.
            delay(SyncEngine.DELETE_GRACE_MS + 1_000)
            result = engine.run(SyncTrigger.LOCAL_CHANGE)
        }
        if (result.shouldRetry) scheduler.requestSync(trigger)
    }

    private companion object {
        const val LOCAL_CHANGE_DEBOUNCE_MS = 2_000L
    }
}
