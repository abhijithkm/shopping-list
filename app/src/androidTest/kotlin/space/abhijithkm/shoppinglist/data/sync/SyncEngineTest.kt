package space.abhijithkm.shoppinglist.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.core.model.ListOrigin
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.SyncType
import space.abhijithkm.shoppinglist.data.remote.AppHubAuthApi
import space.abhijithkm.shoppinglist.data.remote.AppHubConfig
import space.abhijithkm.shoppinglist.data.remote.AppHubTableApi
import space.abhijithkm.shoppinglist.data.remote.SessionAuthInterceptor
import space.abhijithkm.shoppinglist.data.remote.SessionAuthenticator
import space.abhijithkm.shoppinglist.data.remote.Tables
import space.abhijithkm.shoppinglist.data.remote.appHubRetrofit
import space.abhijithkm.shoppinglist.data.repository.AccountScope
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.data.session.AccountInfo
import space.abhijithkm.shoppinglist.data.session.SessionManager
import space.abhijithkm.shoppinglist.data.session.SessionState
import space.abhijithkm.shoppinglist.data.session.SessionStorage
import space.abhijithkm.shoppinglist.data.session.StoredSession

internal const val USER = "user-1"

internal class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

internal class PrefixIds(private val prefix: String) : IdGenerator {
    private var n = 0
    override fun newId() = "$prefix-${++n}"
}

internal class MemStorage(var stored: StoredSession?) : SessionStorage {
    override suspend fun load() = stored

    override suspend fun save(session: StoredSession) {
        stored = session
    }

    override suspend fun clear() {
        stored = null
    }
}

/**
 * One simulated phone: its own database, session and engine, talking to the shared [FakeAppHub]. The session is
 * signed in from the start; [activeAccount] is what new rows are stamped with (null makes new lists guest lists,
 * as before signing in).
 */
internal class Device(val hub: FakeAppHub, val name: String) {
    var activeAccount: String? = USER
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()
    val clock = TestClock()
    val session: SessionManager
    val engine: SyncEngine
    val repo: LocalShoppingRepository

    init {
        val (_, refresh) = hub.issueTokens(USER)
        val storage = MemStorage(StoredSession(AccountInfo(USER, "$USER@example.com"), refresh))
        val config = AppHubConfig(hub.baseUrl, "shoppinglistdev")
        val plain = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        session = SessionManager(appHubRetrofit(config, plain).create(AppHubAuthApi::class.java), storage, clock)
        val authed = plain.newBuilder()
            .addInterceptor(SessionAuthInterceptor(session))
            .authenticator(SessionAuthenticator(session))
            .build()
        val tables = appHubRetrofit(config, authed).create(AppHubTableApi::class.java)
        engine = SyncEngine(db, tables, session, clock, minRequestGapMs = 0)
        repo = LocalShoppingRepository(db, clock, PrefixIds(name), AccountScope { activeAccount })
    }

    fun run(trigger: SyncTrigger = SyncTrigger.MANUAL): SyncRunResult = runBlocking { engine.run(trigger) }

    fun dirty(): Int = runBlocking { db.syncDao().observeDirtyCount(USER).first() }

    fun item(id: String) = runBlocking { db.itemDao().getById(id) }

    fun list(id: String) = runBlocking { db.listDao().getById(id) }

    /** Edits an item the way the edit sheet does, changing only what is given. */
    fun edit(id: String, name: String? = null, notes: String? = null, tags: Set<String>? = null) = runBlocking {
        val current = db.itemDao().getById(id)!!
        val currentTags = db.itemDao().tagIdsOf(id).toSet()
        repo.updateItem(
            ItemUpdate(
                id = id,
                name = name ?: current.name,
                quantity = current.quantity,
                unit = current.unit,
                categoryKey = current.categoryKey,
                notes = notes ?: current.notes,
                tagIds = tags ?: currentTags,
            ),
        )
    }

    fun close() = db.close()
}

internal fun Map<String, JsonElement>.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private lateinit var hub: FakeAppHub
    private val devices = mutableListOf<Device>()

    @Before
    fun start() {
        hub = FakeAppHub().also { it.start() }
    }

    @After
    fun stop() {
        devices.forEach { runCatching { it.close() } }
        hub.shutdown()
    }

    private fun device(name: String) = Device(hub, name).also { devices += it }

    /** A list with one item "Milk" on [a], synced to the server and then pulled by [b]. */
    private fun syncedMilk(a: Device, b: Device): Pair<String, String> = runBlocking {
        val listId = a.repo.createList("Groceries")
        val milk = a.repo.addItem(listId, "2 kg milk")!!
        assertEquals(SyncOutcome.COMPLETED, a.run().outcome)
        assertEquals(SyncOutcome.COMPLETED, b.run().outcome)
        listId to milk
    }

    @Test
    fun firstSyncUploadsEverythingAndTheNextRunWritesNothing() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries", color = 0xFF112233.toInt(), icon = "🛒")
        val tag = a.repo.createTag("Organic")
        val milk = a.repo.addItem(listId, "2 kg milk")!!
        a.edit(milk, name = "Milk", notes = "semi-skimmed", tags = setOf(tag))
        a.repo.updateCategory("dairy", name = "Milk and cheese")

        val result = a.run()

        assertEquals(SyncOutcome.COMPLETED, result.outcome)
        assertEquals(0, result.failed)
        val list = hub.rows(USER, Tables.LISTS).single()
        assertEquals("Groceries", list.str("name"))
        assertEquals("🛒", list.str("icon"))
        assertEquals(listId, list.str("id"))
        val item = hub.row(USER, Tables.ITEMS, milk)!!
        assertEquals("Milk", item.str("name"))
        assertEquals("semi-skimmed", item.str("notes"))
        assertEquals("""["$tag"]""", item.str("tag_ids"))
        assertEquals("kg", item.str("unit"))
        assertEquals(1, hub.rows(USER, Tables.TAGS).size)
        val category = hub.rows(USER, Tables.CATEGORIES).single()
        assertEquals("dairy", category.str("builtin_key"))
        assertEquals("Milk and cheese", category.str("name"))
        assertNotEquals("the override id is per account, not the bare key", "dairy", category.str("id"))
        assertEquals(0, a.dirty())

        val writes = hub.writes()
        val again = a.run()
        assertEquals(SyncOutcome.COMPLETED, again.outcome)
        assertEquals("an idle run must not write", writes, hub.writes())
        assertEquals(0, again.pushed)
    }

    private fun assertNotEquals(message: String, a: Any?, b: Any?) = assertFalse(message, a == b)

    @Test
    fun onlyTheAccountsOwnRowCountsAsTheOverrideOfABuiltInCategory() = runBlocking {
        val a = device("A")
        a.repo.updateCategory("dairy", name = "Mine")
        a.run()
        // Another row naming the same built-in, with an id that is not the account's deterministic one (other software, or by hand).
        hub.advanceClock(60_000)
        hub.put(
            USER,
            Tables.CATEGORIES,
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("99999999-9999-4999-8999-999999999999"), "builtin_key" to JsonPrimitive("dairy"),
                    "name" to JsonPrimitive("Impostor"), "created_at_ms" to JsonPrimitive(1),
                ),
            ),
        )
        val b = device("B")

        b.run()

        assertEquals("Mine", b.db.categoryDao().getById("dairy")!!.name)
        a.run()
        assertEquals("Mine", a.db.categoryDao().getById("dairy")!!.name)
    }

    @Test
    fun aSecondDevicePullsLists_items_tags_andCategoryOverrides() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        val tag = a.repo.createTag("Organic")
        val milk = a.repo.addItem(listId, "2 kg milk")!!
        a.edit(milk, notes = "semi", tags = setOf(tag))
        a.repo.updateCategory("dairy", name = "Milk and cheese")
        a.run()

        val b = device("B")
        val result = b.run()

        assertEquals(SyncOutcome.COMPLETED, result.outcome)
        assertEquals("Groceries", b.list(listId)!!.name)
        assertEquals(ListOrigin.CLOUD, b.list(listId)!!.origin)
        val item = b.item(milk)!!
        assertEquals("milk", item.name)
        assertEquals(2.0, item.quantity!!, 0.0)
        assertEquals("semi", item.notes)
        assertEquals(listOf(tag), b.db.itemDao().tagIdsOf(milk))
        assertEquals("Milk and cheese", b.db.categoryDao().getById("dairy")!!.name)
        assertEquals("nothing left to push after a pull", 0, b.dirty())
    }

    @Test
    fun aRenameSendsOnlyTheChangedFieldAndReachesTheOtherDevice() = runBlocking {
        val a = device("A")
        val b = device("B")
        val (_, milk) = syncedMilk(a, b)

        a.edit(milk, name = "Oat milk")
        a.run()

        val patch = hub.requests.last { it.method == "PATCH" }
        assertEquals(setOf("name", "updated_at_ms"), patch.body!!.keys)
        b.run()
        assertEquals("Oat milk", b.item(milk)!!.name)
        assertEquals(2.0, b.item(milk)!!.quantity!!, 0.0)
    }

    @Test
    fun editsToDifferentFieldsOnTwoDevicesBothSurvive() = runBlocking {
        val a = device("A")
        val b = device("B")
        val (_, milk) = syncedMilk(a, b)

        a.edit(milk, name = "Oat milk")
        b.edit(milk, notes = "the blue carton")
        a.run()
        val rb = b.run()
        a.run()

        assertEquals(0, rb.conflicts)
        for (d in listOf(a, b)) {
            assertEquals("Oat milk", d.item(milk)!!.name)
            assertEquals("the blue carton", d.item(milk)!!.notes)
        }
        assertEquals("Oat milk", hub.row(USER, Tables.ITEMS, milk)!!.str("name"))
        assertEquals("the blue carton", hub.row(USER, Tables.ITEMS, milk)!!.str("notes"))
    }

    @Test
    fun theSameFieldEditedOnTwoDevicesIsAConflictAndTheLastToSyncWins() = runBlocking {
        val a = device("A")
        val b = device("B")
        val (_, milk) = syncedMilk(a, b)

        a.edit(milk, name = "From A")
        b.edit(milk, name = "From B")
        a.run()
        val rb = b.run()
        a.run()

        assertEquals(1, rb.conflicts)
        assertEquals("From B", b.item(milk)!!.name)
        assertEquals("From B", hub.row(USER, Tables.ITEMS, milk)!!.str("name"))
        assertEquals("From B", a.item(milk)!!.name)
    }

    @Test
    fun deleteAndUndoPropagate() = runBlocking {
        val a = device("A")
        val b = device("B")
        val (_, milk) = syncedMilk(a, b)

        a.repo.deleteItem(milk)
        a.run()
        b.run()
        assertNotNull(b.item(milk)!!.deletedAt)
        assertEquals(emptyList<String>(), b.repo.observeItems(b.item(milk)!!.listId).first().map { it.name })

        a.repo.restoreItem(milk)
        a.run()
        b.run()
        assertNull(b.item(milk)!!.deletedAt)
    }

    @Test
    fun aDeletedListReachesTheOtherDeviceAsATombstone() = runBlocking {
        val a = device("A")
        val b = device("B")
        val (listId, _) = syncedMilk(a, b)

        a.repo.deleteList(listId)
        a.run()
        b.run()

        assertNotNull(b.list(listId)!!.deletedAt)
        assertEquals(emptyList<String>(), b.repo.observeLists().first().map { it.name })
    }

    @Test
    fun aRowCreatedAndDeletedBeforeItEverSyncedIsNeverCreatedOnTheServer() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        val doomed = a.repo.addItem(listId, "temp")!!
        a.repo.deleteItem(doomed)

        val early = a.run()
        assertEquals("the delete is inside its undo window: left alone", 1, early.deferred)
        assertNotNull("Undo still has something to restore", a.item(doomed))
        a.repo.restoreItem(doomed)
        a.repo.deleteItem(doomed)

        a.clock.now += 20_000
        val result = a.run()

        assertEquals(0, result.deferred)
        assertEquals(1, hub.rows(USER, Tables.LISTS).size)
        assertTrue(hub.rows(USER, Tables.ITEMS).isEmpty())
        assertEquals("no create was ever sent for it", 0, hub.count("POST", Tables.ITEMS))
        assertNull("the tombstone is dropped locally once the server confirmed it never had the row", a.item(doomed))
        assertEquals(0, a.dirty())
    }

    @Test
    fun aDeleteThatFollowsALostCreateReplyStillReachesTheServer() = runBlocking {
        val a = device("A")
        a.run() // sets the pull time so the later run only pushes
        val listId = a.repo.createList("Groceries")
        a.run(SyncTrigger.LOCAL_CHANGE) // the list is on the server
        val item = a.repo.addItem(listId, "milk")!!
        // The create reached the server but its answer was lost: the server has the row, the device does not know.
        hub.put(
            USER,
            Tables.ITEMS,
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(item), "list_id" to JsonPrimitive(listId), "name" to JsonPrimitive("milk"),
                    "created_at_ms" to JsonPrimitive(1), "updated_at_ms" to JsonPrimitive(0), "is_bought" to JsonPrimitive(0),
                    "sort_order" to JsonPrimitive(1.0),
                ),
            ),
        )
        a.repo.deleteItem(item)
        a.clock.now += 20_000

        a.run(SyncTrigger.LOCAL_CHANGE)

        assertNotNull("the server row became a tombstone", hub.row(USER, Tables.ITEMS, item)!!.str("deleted_at_ms"))
        assertEquals(0, a.dirty())
    }

    @Test
    fun movingAStaleDetachedListBackDoesNotOverwriteNewerServerDataOrResurrectDeletes() = runBlocking {
        val a = device("A")
        val b = device("B")
        val (listId, milk) = syncedMilk(a, b)
        // Phone A signs out (its lists become guest data) ...
        SyncRepository(a.db, a.engine, a.session).signOut()
        // ... meanwhile phone B (whose clock is later) renames the list and deletes the item.
        b.clock.now += 120_000
        b.repo.renameList(listId, "Renamed on B")
        b.repo.deleteItem(milk)
        b.clock.now += 20_000
        b.run()
        assertEquals("Renamed on B", hub.row(USER, Tables.LISTS, listId)!!.str("name"))
        assertNotNull(hub.row(USER, Tables.ITEMS, milk)!!.str("deleted_at_ms"))
        // Phone A signs in again and moves the old guest list back.
        a.activeAccount = USER
        a.session.signIn("$USER@example.com", "pw")
        a.run()

        MigrationRepository(a.db, a.session, a.engine).migrate(setOf(listId))

        assertEquals("the newer server name is kept", "Renamed on B", hub.row(USER, Tables.LISTS, listId)!!.str("name"))
        assertNotNull("the delete is not undone", hub.row(USER, Tables.ITEMS, milk)!!.str("deleted_at_ms"))
        assertEquals(1, hub.rows(USER, Tables.LISTS).size)
    }

    @Test
    fun itemsOfAListDeletedBeforeThisDeviceArrivedDoNotHoldTheCursorBack() = runBlocking {
        hub.put(USER, Tables.LISTS, row("L-dead", "name" to JsonPrimitive("Gone"), "is_archived" to JsonPrimitive(0), "is_pinned" to JsonPrimitive(0), "sort_key" to JsonPrimitive(1.0), "deleted_at_ms" to JsonPrimitive(5)))
        hub.put(USER, Tables.ITEMS, row("I-1", "list_id" to JsonPrimitive("L-dead"), "name" to JsonPrimitive("left behind"), "is_bought" to JsonPrimitive(0), "sort_order" to JsonPrimitive(1.0)))
        val b = device("B")

        b.run()

        assertNull(b.list("L-dead"))
        assertNull(b.item("I-1"))
        assertNotNull("the cursor moved on: the dead list's items are not waited for", b.db.syncDao().state(USER, "cursor.item"))
    }

    @Test
    fun aTagThatArrivesLaterRestoresTheLinksOfItemsAlreadyStored() = runBlocking {
        val a = device("A")
        val b = device("B")
        val listId = a.repo.createList("Groceries")
        val tag = a.repo.createTag("Organic")
        val item = a.repo.addItem(listId, "milk")!!
        a.edit(item, tags = setOf(tag))
        a.run()
        hub.refusals["GET" to Tables.TAGS] = 500 // B cannot read the tags yet ...

        b.run() // ... so it stores the item without its tag link and moves the item cursor on
        assertEquals(emptyList<String>(), b.db.itemDao().tagIdsOf(item))
        hub.advanceClock(600_000)
        a.repo.addItem(listId, "eggs")
        a.run()
        b.run() // the item cursor is now far ahead of the first item
        hub.refusals.clear()

        b.run() // the tag finally arrives

        assertEquals("the item is read again and linked", listOf(tag), b.db.itemDao().tagIdsOf(item))
    }

    @Test
    fun anOutageThatAnswers500DoesNotPenaliseTheRowsItHit() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        a.run()
        val first = a.repo.addItem(listId, "item 0")!!
        repeat(3) { a.repo.addItem(listId, "item ${it + 1}") }
        hub.refusals["POST" to Tables.ITEMS] = 500

        repeat(3) {
            assertEquals(SyncOutcome.SERVER_UNAVAILABLE, a.run().outcome)
            a.clock.now += 40_000
        }

        assertEquals("three outages in a row must not block anything", 0, a.db.syncDao().observeBlockedCount().first())
        assertNull("the penalty was undone", a.db.syncDao().entity(SyncType.ITEM, first))
        hub.refusals.clear()
        assertEquals(SyncOutcome.COMPLETED, a.run().outcome)
        assertEquals(4, hub.rows(USER, Tables.ITEMS).size)
    }

    @Test
    fun aCreateWhoseAnswerWasLostIsAdoptedNotDuplicated() = runBlocking {
        val a = device("A")
        a.run() // first run pulls; later LOCAL_CHANGE runs within a minute push only
        val listId = a.repo.createList("Mine")
        hub.put(
            USER,
            Tables.LISTS,
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(listId), "name" to JsonPrimitive("Old name"), "created_at_ms" to JsonPrimitive(1),
                    "updated_at_ms" to JsonPrimitive(0), "is_archived" to JsonPrimitive(0), "is_pinned" to JsonPrimitive(0),
                    "sort_key" to JsonPrimitive(1.0),
                ),
            ),
        )

        val result = a.run(SyncTrigger.LOCAL_CHANGE)

        assertEquals(SyncOutcome.COMPLETED, result.outcome)
        assertEquals("exactly one row on the server", 1, hub.rows(USER, Tables.LISTS).size)
        assertEquals("Mine", hub.row(USER, Tables.LISTS, listId)!!.str("name"))
        assertTrue("the 409 led to a lookup", hub.requests.any { it.method == "GET" && it.path.endsWith("/shopping_lists/$listId") })
        assertEquals(0, a.dirty())
    }

    @Test
    fun offlineKeepsTheEditAndTheNextRunSendsIt() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        a.run()

        hub.goOffline()
        a.repo.renameList(listId, "Weekly shop")
        val offline = a.run()
        assertEquals(SyncOutcome.OFFLINE, offline.outcome)
        assertTrue(offline.shouldRetry)
        assertEquals(1, a.dirty())
        assertEquals("Weekly shop", a.list(listId)!!.name)

        hub.goOnline()
        assertEquals(SyncOutcome.COMPLETED, a.run().outcome)
        assertEquals("Weekly shop", hub.row(USER, Tables.LISTS, listId)!!.str("name"))
        assertEquals(0, a.dirty())
    }

    @Test
    fun aRefusedRowIsBlockedWhileTheRestSyncs_andRetryUnblocksIt() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        val bad = a.repo.addItem(listId, "bad")!!
        hub.refusals["POST" to Tables.ITEMS] = 422

        val first = a.run()

        assertEquals(SyncOutcome.COMPLETED, first.outcome)
        assertEquals(1, first.failed)
        assertEquals("the list still synced", 1, hub.rows(USER, Tables.LISTS).size)
        assertTrue(a.db.syncDao().entity(SyncType.ITEM, bad)!!.blocked)
        val posts = hub.count("POST", Tables.ITEMS)

        hub.refusals.clear()
        a.run()
        assertEquals("a blocked row waits instead of hammering the server", posts, hub.count("POST", Tables.ITEMS))
        assertEquals(1, a.dirty())

        a.db.syncDao().unblockAll()
        a.run()
        assertEquals(0, a.dirty())
        assertNotNull(hub.row(USER, Tables.ITEMS, bad))
    }

    @Test
    fun aRefusedRowIsUnblockedByANewEdit() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        val bad = a.repo.addItem(listId, "bad")!!
        hub.refusals["POST" to Tables.ITEMS] = 422
        a.run()
        hub.refusals.clear()

        a.edit(bad, name = "fixed")
        a.run()

        assertEquals("fixed", hub.row(USER, Tables.ITEMS, bad)!!.str("name"))
        assertEquals(0, a.dirty())
    }

    @Test
    fun serverErrorsBackOffAndBlockAfterThreeAttempts() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        val item = a.repo.addItem(listId, "milk")!!
        hub.refusals["POST" to Tables.ITEMS] = 500

        a.run()
        val afterFirst = hub.count("POST", Tables.ITEMS)
        a.run()
        assertEquals("inside the backoff window nothing is retried", afterFirst, hub.count("POST", Tables.ITEMS))

        a.clock.now += 6_000
        a.run()
        a.clock.now += 31_000
        a.run()
        val entity = a.db.syncDao().entity(SyncType.ITEM, item)!!
        assertEquals(3, entity.failCount)
        assertTrue("blocked after three failures", entity.blocked)
        assertTrue(entity.lastError!!.startsWith("HTTP 500"))
    }

    @Test
    fun anEndedSessionStopsTheRunButKeepsEverythingLocal() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        a.repo.addItem(listId, "milk")
        hub.rejectAccessTokens = true
        hub.revokeAllRefreshTokens()

        val result = a.run()

        assertEquals(SyncOutcome.NEEDS_SIGN_IN, result.outcome)
        assertTrue(a.session.state.value is SessionState.NeedsReauth)
        assertEquals("Groceries", a.list(listId)!!.name)
        assertEquals(2, a.dirty())
        assertEquals(SyncOutcome.NEEDS_SIGN_IN, a.run().outcome)
    }

    @Test
    fun aRateLimitStopsTheRunAndReportsRetryAfter() = runBlocking {
        val a = device("A")
        a.repo.createList("Groceries")
        hub.failNext = 429 to 5

        val result = a.run()

        assertEquals(SyncOutcome.RATE_LIMITED, result.outcome)
        assertEquals(1_000L, result.retryAfterMs)
        assertTrue(result.shouldRetry)
        assertEquals(1, a.dirty())
    }

    @Test
    fun manyRowsArePulledAcrossPages() = runBlocking {
        hub.put(USER, Tables.LISTS, row("L1", "name" to JsonPrimitive("Big"), "is_archived" to JsonPrimitive(0), "is_pinned" to JsonPrimitive(0), "sort_key" to JsonPrimitive(1.0)))
        repeat(130) { n ->
            hub.put(USER, Tables.ITEMS, row("I-$n", "list_id" to JsonPrimitive("L1"), "name" to JsonPrimitive("item $n"), "is_bought" to JsonPrimitive(0), "sort_order" to JsonPrimitive(n.toDouble())))
        }
        val b = device("B")

        val result = b.run()

        assertEquals(SyncOutcome.COMPLETED, result.outcome)
        assertEquals(130, b.db.itemDao().allOfList("L1").size)
        assertEquals(2, hub.requests.count { it.method == "GET" && it.path.endsWith("/shopping_items") })
        val quiet = b.run()
        assertEquals(0, quiet.pulled)
        assertEquals(130, b.db.itemDao().allOfList("L1").size)
    }

    @Test
    fun anItemWhoseListHasNotArrivedIsKeptForLater() = runBlocking {
        hub.put(USER, Tables.ITEMS, row("I-1", "list_id" to JsonPrimitive("L9"), "name" to JsonPrimitive("orphan"), "is_bought" to JsonPrimitive(0), "sort_order" to JsonPrimitive(1.0)))
        val b = device("B")

        b.run()
        assertNull(b.item("I-1"))
        assertNull("the cursor must not move past a row that could not be placed", b.db.syncDao().state(USER, "cursor.item"))

        hub.put(USER, Tables.LISTS, row("L9", "name" to JsonPrimitive("Late"), "is_archived" to JsonPrimitive(0), "is_pinned" to JsonPrimitive(0), "sort_key" to JsonPrimitive(1.0)))
        b.run()
        assertEquals("orphan", b.item("I-1")!!.name)
    }

    @Test
    fun deletingATagSyncsTheTagAndTheItemsThatCarriedIt() = runBlocking {
        val a = device("A")
        val b = device("B")
        val listId = a.repo.createList("Groceries")
        val tag = a.repo.createTag("Organic")
        val item = a.repo.addItem(listId, "milk")!!
        a.edit(item, tags = setOf(tag))
        a.run()
        b.run()
        assertEquals(listOf(tag), b.db.itemDao().tagIdsOf(item))

        a.repo.deleteTag(tag)
        a.run()
        b.run()

        assertNotNull(hub.row(USER, Tables.TAGS, tag)!!.str("deleted_at_ms"))
        // The item's tag_ids on the server may still name the deleted tag: references to deleted tags are ignored on read.
        assertNotNull(b.db.tagDao().getById(tag)!!.deletedAt)
        assertEquals(emptyList<String>(), b.db.itemDao().tagIdsOf(item))
    }

    @Test
    fun signingOutKeepsEveryListAsGuestDataAndStopsSyncing() = runBlocking {
        val a = device("A")
        val listId = a.repo.createList("Groceries")
        val milk = a.repo.addItem(listId, "milk")!!
        a.run()
        a.repo.renameList(listId, "Weekly") // not synced yet when the user signs out

        val report = SyncRepository(a.db, a.engine, a.session).signOut()

        assertEquals(SessionState.SignedOut, a.session.state.value)
        assertEquals("a last sync was attempted first", "Weekly", hub.row(USER, Tables.LISTS, listId)!!.str("name"))
        assertEquals(0, report.unsyncedChanges)
        val list = a.list(listId)!!
        assertNull(list.accountId)
        assertEquals(ListOrigin.LOCAL, list.origin)
        assertNull(a.db.syncDao().entity(SyncType.LIST, listId))
        assertNotNull(a.item(milk))
        assertNull(a.item(milk)!!.deletedAt)
        assertEquals(SyncOutcome.NOT_SIGNED_IN, a.run().outcome)
    }

    private fun row(id: String, vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "created_at_ms" to JsonPrimitive(1),
            "updated_at_ms" to JsonPrimitive(0),
        ) + fields,
    )
}
