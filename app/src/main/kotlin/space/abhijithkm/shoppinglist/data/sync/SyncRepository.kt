package space.abhijithkm.shoppinglist.data.sync

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.remote.AppHubJson
import space.abhijithkm.shoppinglist.data.session.AccountInfo
import space.abhijithkm.shoppinglist.data.session.SessionManager
import space.abhijithkm.shoppinglist.data.session.SessionState

/**
 * Account data becomes guest data: no account, no server revision, no sync bookkeeping. The account's own lists, items,
 * categories and tags are never deleted. Lists that other people own and share with the account are not the account's
 * data to keep: they leave the device with the account and come back at the next sign-in, as do the cached memberships,
 * invitations and join requests.
 */
internal suspend fun detachAccountData(db: AppDatabase, accountId: String) {
    val sync = db.syncDao()
    // Bookkeeping first, while the rows still say whose they are.
    sync.deleteEntitiesOfAccount(accountId)
    db.listDao().purgeSharedOf(accountId)
    db.listDao().detachAccount(accountId)
    CollabStore(db).clear(accountId)
    db.categoryDao().detachAccount(accountId)
    db.tagDao().detachAccount(accountId)
    sync.clearState(accountId)
}

/**
 * Detaches the data of every account other than [keep]. Safe to repeat: it is called after a sign-in and again at the
 * start of every sync run, so an interrupted account switch is finished instead of leaving one account's rows
 * tagged with another account's id.
 */
internal suspend fun detachOtherAccounts(db: AppDatabase, keep: String) {
    db.withTransaction {
        db.syncDao().allAccountIds().filter { it != keep }.forEach { detachAccountData(db, it) }
    }
}

enum class SyncPhase {
    /** No account: everything stays on this device. */
    SIGNED_OUT,

    /** The session ended; local data is safe and edits queue up until the user signs in again. */
    NEEDS_SIGN_IN,
    SYNCING,

    /** Changes are waiting to be sent (offline, or a retry is scheduled). */
    PENDING,

    /** Some changes were refused by the server and wait for a new edit or a retry. */
    HAS_FAILURES,
    UP_TO_DATE,
}

/** What the UI shows about sync. */
data class SyncStatus(
    val phase: SyncPhase,
    val account: AccountInfo? = null,
    /** Rows waiting to be sent, not counting [failed]. */
    val pending: Int = 0,
    val failed: Int = 0,
    /** Epoch ms of the last run that finished with no failures, or null if none yet. */
    val lastSuccessAt: Long? = null,
    /** Conflicts (the same field changed on two devices) resolved by the latest run. */
    val lastConflicts: Int = 0,
    val lastOutcome: SyncOutcome? = null,
)

data class SignOutReport(
    /** Changes that had not reached the server when the user signed out (kept on the device as guest data). */
    val unsyncedChanges: Int,
    /** Edits to lists other people own that had not reached the server: they are not kept (those lists leave with the account). */
    val lostSharedChanges: Int = 0,
)

/**
 * UI-facing side of sync: observable status, manual actions, and what happens to local data when the account
 * changes. Nothing here deletes lists or items: signing out detaches the account's data and keeps it as guest data.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val db: AppDatabase,
    private val engine: SyncEngine,
    private val session: SessionManager,
) {
    private val sync = db.syncDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    val status: Flow<SyncStatus> = session.state.flatMapLatest { state ->
        val account = when (state) {
            is SessionState.SignedIn -> state.account
            is SessionState.NeedsReauth -> state.account
            else -> null
        }
        if (account == null) {
            flowOf(SyncStatus(SyncPhase.SIGNED_OUT))
        } else {
            combine(
                sync.observeDirtyCount(account.userId),
                sync.observeBlockedCount(),
                sync.observeState(account.userId, SyncEngine.LAST_SUCCESS_AT),
                sync.observeState(account.userId, SyncEngine.LAST_RUN),
                engine.running,
            ) { dirty, blocked, lastSuccess, lastRun, running ->
                val failed = blocked.coerceAtMost(dirty)
                val pending = (dirty - failed).coerceAtLeast(0)
                val summary = lastRun?.let(::parseSummary)
                SyncStatus(
                    phase = when {
                        state is SessionState.NeedsReauth -> SyncPhase.NEEDS_SIGN_IN
                        running -> SyncPhase.SYNCING
                        failed > 0 -> SyncPhase.HAS_FAILURES
                        pending > 0 -> SyncPhase.PENDING
                        else -> SyncPhase.UP_TO_DATE
                    },
                    account = account,
                    pending = pending,
                    failed = failed,
                    lastSuccessAt = lastSuccess?.toLongOrNull(),
                    lastConflicts = summary?.conflicts ?: 0,
                    lastOutcome = summary?.outcome,
                )
            }
        }
    }

    suspend fun syncNow(): SyncRunResult = engine.run(SyncTrigger.MANUAL)

    /** Gives rows the server refused another chance now (they are retried as they are). */
    suspend fun retryFailed(): SyncRunResult {
        sync.unblockAll()
        return engine.run(SyncTrigger.MANUAL)
    }

    /** Forgets the pull cursors, so the next run reads everything again (repairs a missed change). */
    suspend fun fullResync(): SyncRunResult {
        session.account?.userId?.let { sync.clearCursors(it) }
        return engine.run(SyncTrigger.MANUAL)
    }

    /**
     * Called after a sign-in. Data that still belongs to a different account is detached and kept as guest data, so
     * two accounts on one device never mix and nothing is deleted.
     */
    suspend fun onSignedIn(account: AccountInfo) {
        detachOtherAccounts(db, account.userId)
    }

    /**
     * Signs out. A last sync is attempted for a few seconds first; whatever could not be sent stays on the device as
     * guest data (never deleted) and is reported so the user knows.
     */
    suspend fun signOut(): SignOutReport {
        session.restore()
        val account = session.account?.userId
        if (account != null) withTimeoutOrNull(FINAL_SYNC_TIMEOUT_MS) { engine.run(SyncTrigger.MANUAL) }
        val unsynced = account?.let { sync.observeDirtyCount(it).first() } ?: 0
        val lostShared = account?.let { sync.dirtySharedItemCount(it) } ?: 0
        // Both steps finish even if the caller goes away: a half sign-out would leave account data without a session.
        withContext(NonCancellable) {
            session.signOut()
            if (account != null) db.withTransaction { detachAccountData(db, account) }
        }
        return SignOutReport(unsynced - lostShared, lostShared)
    }

    private class Summary(val conflicts: Int, val outcome: SyncOutcome?)

    private fun parseSummary(text: String): Summary? = runCatching {
        val o = AppHubJson.parseToJsonElement(text).jsonObject
        Summary(
            conflicts = o["conflicts"]?.jsonPrimitive?.intOrNull ?: 0,
            outcome = o["outcome"]?.jsonPrimitive?.content?.let { runCatching { SyncOutcome.valueOf(it) }.getOrNull() },
        )
    }.getOrNull()

    private companion object {
        const val FINAL_SYNC_TIMEOUT_MS = 15_000L
    }
}
