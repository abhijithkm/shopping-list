package space.abhijithkm.shoppinglist.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.core.model.ListOrigin
import space.abhijithkm.shoppinglist.data.remote.Tables

/** Guest-to-account migration against the in-memory fake AppHub. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
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

    /** A phone whose lists so far are guest lists: signed in, but new rows are not stamped with the account yet. */
    private fun guestDevice(name: String) = Device(hub, name).also {
        it.activeAccount = null
        devices += it
    }

    private fun migration(d: Device) = MigrationRepository(d.db, d.session, d.engine)

    @Test
    fun theSelectedListsUploadWithEverythingTheyContain() = runBlocking {
        val d = guestDevice("G")
        val groceries = d.repo.createList("Groceries", color = 0xFF112233.toInt(), icon = "🛒")
        val hardware = d.repo.createList("Hardware")
        val tag = d.repo.createTag("Organic")
        val custom = d.repo.createCategory("Pets", color = 0xFF00AA00.toInt())
        val milk = d.repo.addItem(groceries, "2 kg milk")!!
        val eggs = d.repo.addItem(groceries, "12 eggs")!!
        val nails = d.repo.addItem(hardware, "nails")!!
        d.edit(milk, notes = "semi", tags = setOf(tag))
        d.repo.setItemBought(eggs, true)
        d.repo.moveItem(eggs, aboveId = null, belowId = milk) // eggs now first

        val plan = migration(d).plan()
        assertEquals(setOf("Groceries", "Hardware"), plan.lists.map { it.name }.toSet())
        assertEquals(2, plan.lists.first { it.name == "Groceries" }.itemCount)
        assertEquals(2, plan.vocabularyCount)

        val result = migration(d).migrate(setOf(groceries), includeVocabulary = true)

        assertEquals(SyncOutcome.COMPLETED, result.outcome)
        assertEquals(1, result.listsMoved)
        val list = hub.row(USER, Tables.LISTS, groceries)!!
        assertEquals("Groceries", list.str("name"))
        assertEquals("🛒", list.str("icon"))
        val serverMilk = hub.row(USER, Tables.ITEMS, milk)!!
        assertEquals("semi", serverMilk.str("notes"))
        assertEquals("""["$tag"]""", serverMilk.str("tag_ids"))
        val serverEggs = hub.row(USER, Tables.ITEMS, eggs)!!
        assertNotNull("bought state travels", serverEggs.str("bought_at_ms"))
        assertEquals(
            "manual order travels",
            d.item(eggs)!!.sortOrder,
            (hub.row(USER, Tables.ITEMS, eggs)!!["sort_order"] as JsonPrimitive).content.toDouble(),
            0.0,
        )
        assertNotNull(hub.row(USER, Tables.TAGS, tag))
        assertEquals("Pets", hub.row(USER, Tables.CATEGORIES, custom)!!.str("name"))
        assertNull("the list that was not selected stays on the device only", hub.row(USER, Tables.LISTS, hardware))
        assertNull(hub.row(USER, Tables.ITEMS, nails))
    }

    @Test
    fun nothingIsDeletedAndTheUnselectedListStaysAGuestList() = runBlocking {
        val d = guestDevice("G")
        val moved = d.repo.createList("Moved")
        val kept = d.repo.createList("Kept")
        val keptItem = d.repo.addItem(kept, "stays")!!

        migration(d).migrate(setOf(moved))

        assertEquals(ListOrigin.CLOUD, d.list(moved)!!.origin)
        assertEquals(USER, d.list(moved)!!.accountId)
        assertNull(d.list(kept)!!.accountId)
        assertEquals(ListOrigin.LOCAL, d.list(kept)!!.origin)
        assertEquals("stays", d.item(keptItem)!!.name)
        assertNull(d.item(keptItem)!!.deletedAt)
        assertEquals(1, migration(d).plan().lists.size)
    }

    @Test
    fun runningTheMigrationAgainDuplicatesNothing() = runBlocking {
        val d = guestDevice("G")
        val id = d.repo.createList("Groceries")
        d.repo.addItem(id, "milk")
        migration(d).migrate(setOf(id))
        val writes = hub.writes()

        val again = migration(d).migrate(setOf(id))

        assertEquals(SyncOutcome.COMPLETED, again.outcome)
        assertEquals(1, hub.rows(USER, Tables.LISTS).size)
        assertEquals(1, hub.rows(USER, Tables.ITEMS).size)
        assertEquals("no further writes", writes, hub.writes())
    }

    @Test
    fun aSameNamedListInTheAccountIsFlaggedNotMergedSilently() = runBlocking {
        val account = Device(hub, "A").also { devices += it }
        account.repo.createList("Groceries")
        account.run()
        val d = guestDevice("G")
        val mine = d.repo.createList("groceries ") // same name, different case and spacing
        d.run() // the first sync after sign-in: pull, so the account's lists are known

        val plan = migration(d).plan()

        assertTrue(plan.lists.single().nameClash)
        migration(d).migrate(setOf(mine))
        assertEquals("both lists exist: nothing was merged or overwritten", 2, hub.rows(USER, Tables.LISTS).size)
    }

    @Test
    fun anOfflineMigrationIsRecoverable() = runBlocking {
        val d = guestDevice("G")
        val id = d.repo.createList("Groceries")
        val milk = d.repo.addItem(id, "milk")!!
        d.run() // establishes the pull time so the later runs only push
        hub.goOffline()

        val result = migration(d).migrate(setOf(id))

        assertEquals(SyncOutcome.OFFLINE, result.outcome)
        assertEquals("the list already belongs to the account locally", USER, d.list(id)!!.accountId)
        assertTrue(d.dirty() >= 2)
        assertNotNull(d.item(milk))

        hub.goOnline()
        assertEquals(SyncOutcome.COMPLETED, d.run().outcome)
        assertEquals(1, hub.rows(USER, Tables.LISTS).size)
        assertEquals(1, hub.rows(USER, Tables.ITEMS).size)
        assertEquals(0, d.dirty())
    }

    @Test
    fun aPartialEarlierUploadIsCompletedNotDuplicated() = runBlocking {
        val d = guestDevice("G")
        val id = d.repo.createList("Groceries")
        d.run()
        // A previous attempt got the list to the server but crashed before recording it.
        hub.put(
            USER,
            Tables.LISTS,
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(id), "name" to JsonPrimitive("Groceries"), "created_at_ms" to JsonPrimitive(1),
                    "updated_at_ms" to JsonPrimitive(0), "is_archived" to JsonPrimitive(0), "is_pinned" to JsonPrimitive(0),
                    "sort_key" to JsonPrimitive(1.0),
                ),
            ),
        )

        migration(d).migrate(setOf(id))

        assertEquals(1, hub.rows(USER, Tables.LISTS).size)
        assertEquals(0, d.dirty())
    }

    @Test
    fun withNoGuestListsThereIsNothingToOffer() = runBlocking {
        val d = guestDevice("G")
        assertTrue(migration(d).plan().isEmpty)
        d.activeAccount = USER
        d.repo.createList("Already in the account")
        assertTrue(migration(d).plan().isEmpty)
    }

    @Test
    fun anInterruptedAccountSwitchIsFinishedByTheNextRun() = runBlocking {
        val d = Device(hub, "A").also { devices += it }
        val id = d.repo.createList("Mine")
        d.run()
        // Another person signs in, and the app is killed before the sign-in screen detached the old account's data.
        d.session.signOut()
        d.session.signIn("other@example.com", "pw")
        assertEquals(USER, d.list(id)!!.accountId)

        val result = d.run()

        assertEquals(SyncOutcome.COMPLETED, result.outcome)
        assertNull(d.list(id)!!.accountId)
        assertTrue("the first account's list was never sent to the second account", hub.rows("other", Tables.LISTS).isEmpty())
        assertEquals(1, hub.rows(USER, Tables.LISTS).size)
    }

    @Test
    fun signingInAsADifferentAccountKeepsTheOldAccountsListsAsGuestData() = runBlocking {
        val d = Device(hub, "A").also { devices += it }
        val id = d.repo.createList("Mine")
        d.run()
        assertEquals(USER, d.list(id)!!.accountId)

        SyncRepository(d.db, d.engine, d.session).onSignedIn(space.abhijithkm.shoppinglist.data.session.AccountInfo("someone-else", "x@example.com"))

        assertNull(d.list(id)!!.accountId)
        assertEquals(ListOrigin.LOCAL, d.list(id)!!.origin)
        assertFalse("a guest list is offered for migration to the new account", migration(d).plan().isEmpty)
        assertEquals(0, d.db.syncDao().observeDirtyCount("someone-else").first())
    }
}
