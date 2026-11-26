package space.abhijithkm.shoppinglist.data.sync

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.SyncEntityEntity
import space.abhijithkm.shoppinglist.data.local.SyncStateEntity
import space.abhijithkm.shoppinglist.data.local.SyncType
import space.abhijithkm.shoppinglist.data.remote.AppHubException
import space.abhijithkm.shoppinglist.data.remote.AppHubTableApi
import space.abhijithkm.shoppinglist.data.collab.isRevisionConflict
import space.abhijithkm.shoppinglist.data.remote.Envelope
import space.abhijithkm.shoppinglist.data.remote.RowCodec
import space.abhijithkm.shoppinglist.data.remote.Tables
import space.abhijithkm.shoppinglist.data.remote.appHubEnvelope
import space.abhijithkm.shoppinglist.data.session.RefreshResult
import space.abhijithkm.shoppinglist.data.session.SessionManager
import space.abhijithkm.shoppinglist.data.session.SessionState

enum class SyncTrigger { MANUAL, PERIODIC, APP_START, SIGN_IN, LOCAL_CHANGE }

enum class SyncOutcome {
    COMPLETED,
    NOT_SIGNED_IN,
    NEEDS_SIGN_IN,
    OFFLINE,
    RATE_LIMITED,
    SERVER_UNAVAILABLE,

    /** The run hit its time budget with work left; schedule another one. */
    INTERRUPTED,
}

data class SyncRunResult(
    val outcome: SyncOutcome,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val failed: Int = 0,
    val retryAfterMs: Long? = null,
    /** Rows left for later on purpose (a delete still inside its undo window). */
    val deferred: Int = 0,
    /** Changes other members made to items of lists that were already on this device: list id to number of items. */
    val sharedChanges: Map<String, Int> = emptyMap(),
    /** Lists the account lost access to in this run (removed by the owner, or left); their local copy is gone. */
    val removedFromLists: Int = 0,
) {
    /** Worth another attempt later without the user doing anything. */
    val shouldRetry: Boolean
        get() = outcome == SyncOutcome.OFFLINE || outcome == SyncOutcome.RATE_LIMITED ||
            outcome == SyncOutcome.SERVER_UNAVAILABLE || outcome == SyncOutcome.INTERRUPTED || deferred > 0
}

/** Ends the current run early; not an error of any single row. */
private class AbortRun(val outcome: SyncOutcome, val retryAfterMs: Long? = null) : Exception(outcome.name, null, false, false)

/** A row was merged with the copy the server already had; its bookkeeping is done, a second push pass sends what is left. */
private class Adopted : Exception(null, null, false, false)

/** Told after every run that reached the server, so local notifications can follow what the sync found. */
fun interface SyncObserver {
    suspend fun onRunFinished(account: String, result: SyncRunResult)
}

/** Mutable state of one run. */
private class Run(val account: String, val deadlineMs: Long) {
    var pushed = 0
    var pulled = 0
    var conflicts = 0
    var failed = 0
    var pullErrors = 0
    var deferred = 0
    var consecutiveServerErrors = 0

    /** Set when a push merged a row with the server's copy: another push pass sends the differences. */
    var again = false

    /** A tag arrived from the server: items are re-read in full so their tag links are not left incomplete. */
    var tagsInserted = false

    /** Ids of lists the server has deleted that this device never saw. */
    val deadParents = HashSet<String>()

    /** Lists that arrived for the first time in this run: their items are not news from other members. */
    val newLists = HashSet<String>()

    /** Items other members changed, per list that was already here. */
    val sharedChanges = HashMap<String, Int>()
    var removedFromLists = 0

    /** The rows behind the current streak of server errors, so an outage does not leave them penalised. */
    val recentServerFailures = ArrayList<Triple<String, String, SyncEntityEntity?>>()
}

/**
 * Pull-then-push synchronisation of the account's lists, items, categories and tags with AppHub
 * (docs/SYNC_STRATEGY.md). Runs one at a time. Safe to interrupt anywhere: every write to the server is
 * idempotent (client UUIDs; PATCH of fields), and the local bookkeeping that records a success is written
 * non-cancellably right after the answer arrives.
 */
@Singleton
class SyncEngine(
    private val db: AppDatabase,
    private val tables: AppHubTableApi,
    private val session: SessionManager,
    private val clock: Clock,
    /** Minimum time between two requests; tests pass 0. */
    private val minRequestGapMs: Long,
    /** Hears about every finished run (local notifications); tests that do not care pass nothing. */
    private val observer: SyncObserver? = null,
) {
    @Inject
    constructor(db: AppDatabase, tables: AppHubTableApi, session: SessionManager, clock: Clock, observer: SyncObserver) :
        this(db, tables, session, clock, MIN_REQUEST_GAP_MS, observer)

    private val adapters: List<EntityAdapter> =
        listOf(TagAdapter(db, clock), CategoryAdapter(db, clock), ListAdapter(db, clock), ItemAdapter(db, clock))
    private val adapterByType = adapters.associateBy { it.type }
    private var collabUnavailableUntil = 0L
    private val sync = db.syncDao()
    private val collabStore = CollabStore(db)
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    private val mutableLast = MutableStateFlow<SyncRunResult?>(null)

    /** The result of the most recent run in this process (null before the first). */
    val lastResult: StateFlow<SyncRunResult?> = mutableLast.asStateFlow()

    suspend fun run(trigger: SyncTrigger, budgetMs: Long = DEFAULT_BUDGET_MS): SyncRunResult = mutex.withLock {
        mutableRunning.value = true
        try {
            runLocked(trigger, budgetMs).also { mutableLast.value = it }
        } finally {
            mutableRunning.value = false
        }
    }

    private suspend fun runLocked(trigger: SyncTrigger, budgetMs: Long): SyncRunResult {
        session.restore()
        val account = session.account?.userId
        when (session.state.value) {
            is SessionState.SignedIn -> Unit
            is SessionState.NeedsReauth -> return SyncRunResult(SyncOutcome.NEEDS_SIGN_IN)
            else -> return SyncRunResult(SyncOutcome.NOT_SIGNED_IN)
        }
        if (account == null) return SyncRunResult(SyncOutcome.NOT_SIGNED_IN)
        val run = Run(account, clock.nowMillis() + budgetMs)
        // Finish an account switch that was interrupted: another account's rows never take part in this run.
        detachOtherAccounts(db, account)
        return try {
            when (session.ensureAccessToken()) {
                is RefreshResult.Token -> Unit
                RefreshResult.Unavailable -> throw AbortRun(SyncOutcome.OFFLINE)
                RefreshResult.SessionEnded -> throw AbortRun(SyncOutcome.NEEDS_SIGN_IN)
                RefreshResult.NotSignedIn -> throw AbortRun(SyncOutcome.NOT_SIGNED_IN)
            }
            val pull = trigger != SyncTrigger.LOCAL_CHANGE || pullIsDue(run)
            if (pull) {
                // Memberships come before lists and items: a list the account may newly see is older than the cursors
                // and has to be read in full, and a list the account lost has to go before its rows are looked at.
                pullType(run, adapterByType.getValue(SyncType.TAG))
                pullType(run, adapterByType.getValue(SyncType.CATEGORY))
                val readEverything = pullCollab(run)
                pullType(run, adapterByType.getValue(SyncType.LIST), ignoreCursor = readEverything)
                pullType(run, adapterByType.getValue(SyncType.ITEM), ignoreCursor = readEverything)
                putState(run.account, LAST_PULL_AT, clock.nowMillis().toString())
            }
            adapters.forEach { pushType(run, it) }
            if (run.again) {
                // A row was merged with the server's copy; send whatever differs now.
                run.again = false
                adapters.forEach { pushType(run, it) }
            }
            val now = clock.nowMillis()
            if (run.failed == 0 && run.pullErrors == 0) putState(run.account, LAST_SUCCESS_AT, now.toString())
            putState(run.account, LAST_RUN, summaryJson(now, SyncOutcome.COMPLETED, run))
            SyncRunResult(
                SyncOutcome.COMPLETED, run.pushed, run.pulled, run.conflicts, run.failed, deferred = run.deferred,
                sharedChanges = run.sharedChanges, removedFromLists = run.removedFromLists,
            ).also { notifyObserver(run.account, it) }
        } catch (e: AbortRun) {
            putState(run.account, LAST_RUN, summaryJson(clock.nowMillis(), e.outcome, run))
            SyncRunResult(e.outcome, run.pushed, run.pulled, run.conflicts, run.failed, e.retryAfterMs, run.deferred)
        }
    }

    /** A failing notification must never fail a sync that already succeeded. */
    private suspend fun notifyObserver(account: String, result: SyncRunResult) {
        try {
            observer?.onRunFinished(account, result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SyncEngine", "Observer failed", e)
        }
    }

    // ------------------------------------------------------------------------------------- pull

    private suspend fun pullIsDue(run: Run): Boolean {
        val last = sync.state(run.account, LAST_PULL_AT)?.toLongOrNull() ?: return true
        return clock.nowMillis() - last >= PULL_MIN_INTERVAL_MS
    }

    /**
     * Reads one table newest-first until it reaches a row older than the cursor minus a safety overlap. There is no
     * "changed since" filter in AppHub, but `updated_at` is maintained by the database on every real change and is
     * sortable, so the top of the stream is exactly the recent changes. Merging a row twice is harmless.
     */
    private suspend fun pullType(run: Run, adapter: EntityAdapter, ignoreCursor: Boolean = false) {
        val key = "cursor.${adapter.type}"
        // A new tag may be referenced by items that were stored without that link: read every item again this run.
        val storedCursor = sync.state(run.account, key)
        val cursor = if (ignoreCursor || (adapter.type == SyncType.ITEM && run.tagsInserted)) null else storedCursor
        val threshold = cursor?.let { ServerTimestamp.minus(it, OVERLAP_MS) }
        var newest = cursor
        var skippedForParent = false
        var page = 1
        try {
            // No page cap: the run's time budget bounds it, and a cap that still saved the cursor would skip old rows for good.
            pages@ while (true) {
                val envelope = call(run) { tables.list(adapter.table, page = page) }
                val rows = envelope.data.orEmpty()
                for (raw in rows) {
                    val updatedAt = try {
                        adapter.updatedAtOf(raw)
                    } catch (e: AppHubException.Protocol) {
                        run.failed++
                        continue
                    }
                    if (threshold != null && updatedAt != null && ServerTimestamp.isBefore(updatedAt, threshold)) break@pages
                    val result = try {
                        adapter.apply(run.account, raw, run.deadParents)
                    } catch (e: AppHubException.Protocol) {
                        run.failed++
                        continue
                    }
                    result.ignoredId?.let { run.deadParents += it }
                    if (adapter.type == SyncType.TAG && result.outcome == ApplyOutcome.INSERTED) run.tagsInserted = true
                    if (adapter.type == SyncType.LIST && result.outcome == ApplyOutcome.INSERTED) RowCodec.list(raw).id.let { run.newLists += it }
                    result.foreignChangeListId?.let { if (it !in run.newLists) run.sharedChanges.merge(it, 1, Int::plus) }
                    when (result.outcome) {
                        ApplyOutcome.SKIPPED_PARENT_MISSING -> skippedForParent = true
                        ApplyOutcome.IGNORED -> Unit
                        else -> if (result.changed) run.pulled++
                    }
                    run.conflicts += result.conflicts
                    newest = ServerTimestamp.max(newest, updatedAt)
                }
                val pageCount = envelope.meta?.pages ?: 0
                if (rows.size < AppHubTableApi.PAGE_LIMIT || (pageCount in 1..page)) break
                page++
            }
        } catch (e: AppHubException) {
            // A table that cannot be read must not stop the pushes; a run-level problem (offline, 401, 429) does.
            runLevelAbort(e)?.let { throw it }
            run.pullErrors++
            return
        }
        // Never move the cursor past a row that could not be placed yet (its list had not arrived): it is read again next time.
        if (!skippedForParent && newest != null && newest != storedCursor) putState(run.account, key, newest)
    }

    // ---------------------------------------------------------------------------------- collaboration

    /**
     * Reads the account's memberships, invitations and join requests (each a complete snapshot) and mirrors them locally.
     * Returns true when lists and items must be read in full because the account may now see a list that is not here yet.
     * A server that was not set up for sharing answers 404 for the first table: that is "no collaboration here", not an
     * error, and it is not asked again for a few minutes.
     */
    private suspend fun pullCollab(run: Run): Boolean {
        if (clock.nowMillis() < collabUnavailableUntil) return false
        val members = readSnapshot(run, Tables.MEMBERS)
        if (members == null) {
            collabUnavailableUntil = clock.nowMillis() + COLLAB_RETRY_MS
            return false
        }
        val applied = collabStore.applyMembers(run.account, members.map(RowCodec::member))
        run.removedFromLists += applied.removedListIds.size
        readSnapshot(run, Tables.INVITATIONS)?.let { collabStore.applyInvitations(run.account, it.map(RowCodec::invitation)) }
        readSnapshot(run, Tables.JOIN_REQUESTS)?.let { collabStore.applyJoinRequests(run.account, it.map(RowCodec::joinRequest)) }
        return applied.needsFullListPull
    }

    /**
     * Every row of a table the account may see, or null when the table does not exist for this app (404/403) or cannot be
     * read completely right now. A partial snapshot is never applied, because rows missing from it would be forgotten.
     */
    private suspend fun readSnapshot(run: Run, table: String): List<JsonObject>? {
        val rows = ArrayList<JsonObject>()
        var page = 1
        try {
            while (true) {
                val envelope = call(run) { tables.list(table, page = page) }
                val chunk = envelope.data.orEmpty()
                rows += chunk
                val pageCount = envelope.meta?.pages ?: 0
                if (chunk.size < AppHubTableApi.PAGE_LIMIT || (pageCount in 1..page)) return rows
                if (++page > MAX_SNAPSHOT_PAGES) return null
            }
        } catch (e: AppHubException.Http) {
            if (e.status == 404 || e.status == 403) return null
            runLevelAbort(e)?.let { throw it }
            run.pullErrors++
            return null
        } catch (e: AppHubException) {
            runLevelAbort(e)?.let { throw it }
            run.pullErrors++
            return null
        }
    }

    // ------------------------------------------------------------------------------------- push

    private suspend fun pushType(run: Run, adapter: EntityAdapter) {
        for (row in adapter.dirty(run.account)) pushRow(run, adapter, row)
    }

    private suspend fun pushRow(run: Run, adapter: EntityAdapter, row: DirtyRow) {
        var entity = sync.entity(adapter.type, row.localId)
        val now = clock.nowMillis()
        if (entity != null && entity.blocked) {
            // Blocked rows wait for a new edit (a higher revision) or an explicit Retry.
            if (row.revision <= entity.blockedAtRevision) return
            entity = entity.copy(failCount = 0, nextAttemptAt = 0, blocked = false, lastError = null)
        }
        if (entity != null && entity.nextAttemptAt > now) return
        val base = SyncFields.decode(entity?.base)?.let(row.normalizeBase)
        if (base == null && row.deletedAt != null) {
            pushUnsyncedTombstone(run, adapter, row, entity, now)
            return
        }
        // An item can only be created once its list exists on the server.
        row.parentLocalId?.let { parent -> if (sync.entity(SyncType.LIST, parent)?.base == null) return }
        try {
            val pushed = if (base == null) create(run, adapter, row) else patch(run, adapter, row, base, entity?.cloudRevision ?: 0)
            withContext(NonCancellable) {
                db.withTransaction {
                    sync.upsertEntity(
                        SyncEntityEntity(
                            adapter.type,
                            row.localId,
                            base = SyncFields.encode(pushed.base),
                            cloudRevision = pushed.cloudRevision ?: entity?.cloudRevision ?: 0,
                        ),
                    )
                    adapter.markClean(row.localId, row.revision)
                }
            }
            run.consecutiveServerErrors = 0
            run.recentServerFailures.clear()
        } catch (e: Adopted) {
            // Already merged with the server's copy by create(); nothing more to record for this row now.
            run.consecutiveServerErrors = 0
            run.recentServerFailures.clear()
        } catch (e: AppHubException) {
            runLevelAbort(e)?.let { throw it }
            recordFailure(run, adapter, row, entity, e)
        }
    }

    /**
     * A row deleted before this device ever recorded it as synced. It may still exist on the server (a create whose
     * answer was lost, or a list moved back into the account), so the tombstone is sent: a 404 means the server never
     * had it and the local row is dropped; anything else leaves a synced tombstone that housekeeping purges later.
     * Deletes younger than [DELETE_GRACE_MS] wait, so Undo still works and a quick add-then-delete costs nothing.
     */
    private suspend fun pushUnsyncedTombstone(run: Run, adapter: EntityAdapter, row: DirtyRow, entity: SyncEntityEntity?, now: Long) {
        val deletedAt = row.deletedAt ?: return
        if (now - deletedAt < DELETE_GRACE_MS) {
            run.deferred++
            return
        }
        try {
            var existsOnServer = true
            try {
                call(run) {
                    tables.patch(
                        adapter.table,
                        adapter.cloudId(run.account, row.localId),
                        SyncFields.body(mapOf(DELETED_AT to SyncFields.long(deletedAt)), updatedAtExtra(row)),
                    )
                }
                run.pushed++
            } catch (e: AppHubException.Http) {
                if (!e.isNotFound) throw e
                existsOnServer = false
            }
            withContext(NonCancellable) {
                db.withTransaction {
                    if (existsOnServer) {
                        sync.upsertEntity(SyncEntityEntity(adapter.type, row.localId, base = SyncFields.encode(row.fields)))
                        adapter.markClean(row.localId, row.revision)
                    } else {
                        adapter.purgeLocal(row.localId)
                        sync.deleteEntity(adapter.type, row.localId)
                    }
                }
            }
        } catch (e: AppHubException) {
            runLevelAbort(e)?.let { throw it }
            recordFailure(run, adapter, row, entity, e)
        }
    }

    /** POST with the client's UUID. A 409 means the row (or a retried create of it) already exists: adopt it and patch the differences. */
    private suspend fun create(run: Run, adapter: EntityAdapter, row: DirtyRow): Pushed {
        val cloudId = adapter.cloudId(run.account, row.localId)
        val body = SyncFields.body(row.fields, row.createExtras + updatedAtExtra(row))
        val created: JsonObject?
        try {
            created = call(run) { tables.create(adapter.table, body) }.data
            run.pushed++
        } catch (e: AppHubException.Http) {
            if (!e.isConflict) throw e
            val existing = try {
                call(run) { tables.get(adapter.table, cloudId) }.data
            } catch (lookup: AppHubException.Http) {
                if (lookup.isNotFound) null else throw lookup
            } ?: throw e
            // Merge with the copy the server has instead of overwriting it: a delete made elsewhere stays a delete, and
            // the newer of two edited versions wins (see the merge rules). Whatever still differs is sent on the second pass.
            val result = adapter.apply(run.account, existing)
            run.conflicts += result.conflicts
            run.again = true
            throw Adopted()
        }
        return Pushed(row.fields, revisionOf(created))
    }

    private suspend fun patch(run: Run, adapter: EntityAdapter, row: DirtyRow, base: Fields, cloudRevision: Int): Pushed {
        val diff = row.fields.filter { (k, v) -> v != (base[k] ?: JsonNull) }
        if (diff.isEmpty()) return Pushed(base, null)
        val cloudId = adapter.cloudId(run.account, row.localId)
        // An item carries the server's edit counter as it was when this device last saw the row. If another member
        // changed it since, the server refuses (422 sl:conflict) instead of overwriting, and the row is merged below.
        val guard = if (adapter.type == SyncType.ITEM && cloudRevision > 0) mapOf("base_revision" to JsonPrimitive(cloudRevision)) else emptyMap()
        val patched: JsonObject?
        try {
            patched = call(run) { tables.patch(adapter.table, cloudId, SyncFields.body(diff, updatedAtExtra(row) + guard)) }.data
            run.pushed++
        } catch (e: AppHubException.Http) {
            if (e.isRevisionConflict) adoptServerCopy(run, adapter, cloudId)
            if (!e.isNotFound) throw e
            // The row is gone from the server (wiped, or never persisted): create it again from the local copy.
            return create(run, adapter, row)
        }
        return Pushed(base + diff, revisionOf(patched))
    }

    /** Reads the row as the server has it now, merges it with the local edit, and lets a second push pass send what is left. */
    private suspend fun adoptServerCopy(run: Run, adapter: EntityAdapter, cloudId: String): Nothing {
        val current = call(run) { tables.get(adapter.table, cloudId) }.data ?: throw AppHubException.Protocol("The server sent an empty answer.")
        val result = adapter.apply(run.account, current)
        run.conflicts += result.conflicts
        run.again = true
        throw Adopted()
    }

    /** The server's edit counter in a create or PATCH answer; null when the server keeps none. */
    private fun revisionOf(row: JsonObject?): Int? =
        (row?.get("revision") as? JsonPrimitive)?.contentOrNull?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }?.takeIf { it > 0 }

    /** The result of a successful create or PATCH: the new common ancestor and the server's revision counter. */
    private class Pushed(val base: Fields, val cloudRevision: Int?)

    private fun updatedAtExtra(row: DirtyRow): Map<String, JsonElement> =
        row.updatedAtMs?.let { mapOf("updated_at_ms" to JsonPrimitive(it)) } ?: emptyMap()

    private suspend fun recordFailure(run: Run, adapter: EntityAdapter, row: DirtyRow, entity: SyncEntityEntity?, e: AppHubException) {
        val http = e as? AppHubException.Http
        // A 4xx other than rate limiting is the server refusing this content: retrying the same bytes cannot help.
        val permanent = http != null && http.status in 400..499
        val failCount = (entity?.failCount ?: 0) + 1
        val blocked = permanent || failCount >= MAX_ATTEMPTS
        val message = buildString {
            append(http?.let { "HTTP ${it.status}: " }.orEmpty())
            append(e.message.orEmpty())
        }.take(MAX_ERROR_LENGTH)
        if (http != null && http.isServerError) {
            // Remember what the row looked like before, in case this turns out to be an outage rather than the row's fault.
            run.recentServerFailures += Triple(adapter.type, row.localId, sync.entity(adapter.type, row.localId))
        }
        withContext(NonCancellable) {
            sync.upsertEntity(
                (entity ?: SyncEntityEntity(adapter.type, row.localId)).copy(
                    failCount = failCount,
                    nextAttemptAt = clock.nowMillis() + Backoff.delayMs(failCount),
                    lastError = message,
                    blocked = blocked,
                    blockedAtRevision = if (blocked) row.revision else entity?.blockedAtRevision ?: 0,
                ),
            )
        }
        run.failed++
        if (http != null && http.isServerError && ++run.consecutiveServerErrors >= MAX_CONSECUTIVE_SERVER_ERRORS) {
            // Several different rows failing in a row is the server, not the rows: undo the penalty they just got.
            withContext(NonCancellable) {
                run.recentServerFailures.forEach { (type, id, before) ->
                    if (before == null) sync.deleteEntity(type, id) else sync.upsertEntity(before)
                }
            }
            run.recentServerFailures.clear()
            throw AbortRun(SyncOutcome.SERVER_UNAVAILABLE)
        }
    }

    // ------------------------------------------------------------------------------------ plumbing

    /** One request, paced under AppHub's per-IP rate limit and bounded by the run's time budget. */
    private suspend fun <T> call(run: Run, block: suspend () -> Envelope<T>): Envelope<T> {
        if (clock.nowMillis() > run.deadlineMs) throw AbortRun(SyncOutcome.INTERRUPTED)
        val wait = lastRequestAt + minRequestGapMs - clock.nowMillis()
        if (wait > 0) delay(wait)
        lastRequestAt = clock.nowMillis()
        return appHubEnvelope(block)
    }

    /** Problems that make every further request pointless right now, as opposed to one row's problem. */
    private fun runLevelAbort(e: AppHubException): AbortRun? = when {
        e is AppHubException.Network -> AbortRun(SyncOutcome.OFFLINE)
        e is AppHubException.Http && e.isUnauthorized ->
            AbortRun(if (session.state.value is SessionState.NeedsReauth) SyncOutcome.NEEDS_SIGN_IN else SyncOutcome.OFFLINE)
        e is AppHubException.Http && e.isRateLimited -> AbortRun(SyncOutcome.RATE_LIMITED, (e.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_S) * 1000)
        e is AppHubException.Http && e.status in SERVICE_DOWN -> AbortRun(SyncOutcome.SERVER_UNAVAILABLE)
        else -> null
    }

    private suspend fun putState(account: String, key: String, value: String) {
        sync.putState(SyncStateEntity(account, key, value))
    }

    private fun summaryJson(at: Long, outcome: SyncOutcome, run: Run): String = JsonObject(
        mapOf(
            "at" to JsonPrimitive(at),
            "outcome" to JsonPrimitive(outcome.name),
            "pushed" to JsonPrimitive(run.pushed),
            "pulled" to JsonPrimitive(run.pulled),
            "conflicts" to JsonPrimitive(run.conflicts),
            "failed" to JsonPrimitive(run.failed),
        ),
    ).toString()

    companion object {
        const val LAST_SUCCESS_AT = "lastSuccessAt"
        const val LAST_RUN = "lastRun"
        private const val LAST_PULL_AT = "lastPullAt"

        /** Re-read this much of the stream before the cursor, so rows written concurrently with a page fetch are not missed. */
        private const val OVERLAP_MS = 120_000L
        private const val PULL_MIN_INTERVAL_MS = 60_000L
        private const val DEFAULT_BUDGET_MS = 8 * 60_000L

        /** A delete younger than this is left alone so that Undo (and a quick add-then-delete) never reaches the server. */
        const val DELETE_GRACE_MS = 15_000L

        /** AppHub allows about 120 requests a minute per IP; stay just under it. */
        private const val MIN_REQUEST_GAP_MS = 520L
        private const val MAX_ATTEMPTS = 3
        private const val COLLAB_RETRY_MS = 10 * 60_000L
        private const val MAX_SNAPSHOT_PAGES = 20
        private const val MAX_CONSECUTIVE_SERVER_ERRORS = 3
        private const val MAX_ERROR_LENGTH = 200
        private const val DEFAULT_RETRY_AFTER_S = 60L
        private val SERVICE_DOWN = setOf(502, 503, 504)
    }
}
