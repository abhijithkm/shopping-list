package space.abhijithkm.shoppinglist.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.remote.AppHubAuthApi
import space.abhijithkm.shoppinglist.data.remote.AppHubConfig
import space.abhijithkm.shoppinglist.data.remote.AppHubHttp
import space.abhijithkm.shoppinglist.data.remote.AppHubTableApi
import space.abhijithkm.shoppinglist.data.remote.appHubRetrofit
import space.abhijithkm.shoppinglist.data.repository.AccountScope
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.data.session.RefreshResult
import space.abhijithkm.shoppinglist.data.session.SessionManager

/**
 * The real client stack (session, refresh, sync engine, merge, migration) against a real AppHub app. It is
 * skipped unless credentials are passed as instrumentation arguments, so nothing secret is ever in the repository:
 *
 *   gradlew connectedDebugAndroidTest ^
 *     -Pandroid.testInstrumentationRunnerArguments.class=space.abhijithkm.shoppinglist.data.sync.CloudDevAppTest ^
 *     -Pandroid.testInstrumentationRunnerArguments.cloudBaseUrl=https://host/apphub/ ^
 *     -Pandroid.testInstrumentationRunnerArguments.cloudApp=shoppinglistdev ^
 *     -Pandroid.testInstrumentationRunnerArguments.cloudEmail=... -Pandroid.testInstrumentationRunnerArguments.cloudPassword=...
 *
 * Use a throwaway account on a dev app: the roles have no delete permission, so the rows stay until the account is
 * removed. AppHub allows about 10 auth calls a minute per IP, so every sign-in waits a few seconds first.
 */
@RunWith(AndroidJUnit4::class)
class CloudDevAppTest {
    private val args = InstrumentationRegistry.getArguments()
    private val baseUrl = args.getString("cloudBaseUrl")
    private val app = args.getString("cloudApp")
    private val email = args.getString("cloudEmail")
    private val password = args.getString("cloudPassword")
    private val devices = mutableListOf<CloudDevice>()

    @Before
    fun requireCredentials() {
        assumeTrue(
            "pass cloudBaseUrl, cloudApp, cloudEmail and cloudPassword as instrumentation arguments to run this",
            !baseUrl.isNullOrBlank() && !app.isNullOrBlank() && !email.isNullOrBlank() && !password.isNullOrBlank(),
        )
    }

    @After
    fun closeDevices() = devices.forEach { runCatching { it.db.close() } }

    private fun device(name: String) = CloudDevice(name, AppHubConfig(baseUrl!!, app!!)).also { devices += it }

    private fun CloudDevice.signedIn(): CloudDevice {
        Thread.sleep(AUTH_SPACING_MS) // AppHub limits auth calls per minute and IP
        val account = runBlocking { session.signIn(email!!, password!!) }
        activeAccount = account.userId
        return this
    }

    @Test
    fun uploadPullMergeConflictDeleteAndUndoAgainstTheRealApp() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val one = device("P$tag").signedIn()
        val listId = one.repo.createList("Cloud check $tag", color = 0xFF112233.toInt(), icon = "🛒")
        val organic = one.repo.createTag("Organic $tag")
        val milk = one.repo.addItem(listId, "2 kg milk")!!
        val bread = one.repo.addItem(listId, "bread")!!
        one.edit(milk, name = "Milk", notes = "semi-skimmed", tags = setOf(organic))
        one.repo.setItemBought(bread, true)
        one.repo.updateCategory("dairy", name = "Dairy $tag")

        val first = one.run()

        assertEquals(SyncOutcome.COMPLETED, first.outcome)
        assertEquals("nothing may be refused by the real server", 0, first.failed)
        assertEquals(0, one.dirty())
        val idle = one.run()
        assertEquals("an idle run writes nothing", 0, idle.pushed)

        val two = device("Q$tag").signedIn()
        assertEquals(SyncOutcome.COMPLETED, two.run().outcome)
        assertEquals("Cloud check $tag", two.list(listId)!!.name)
        assertEquals(0xFF112233.toInt(), two.list(listId)!!.color)
        assertEquals("🛒", two.list(listId)!!.icon)
        val pulledMilk = two.item(milk)!!
        assertEquals("Milk", pulledMilk.name)
        assertEquals(2.0, pulledMilk.quantity!!, 0.0)
        assertEquals("kg", pulledMilk.unit)
        assertEquals("semi-skimmed", pulledMilk.notes)
        assertEquals(one.item(milk)!!.sortOrder, pulledMilk.sortOrder, 0.0)
        assertEquals(listOf(organic), two.db.itemDao().tagIdsOf(milk))
        assertTrue(two.item(bread)!!.isBought)
        assertNotNull("the bought time travelled", two.item(bread)!!.boughtAt)
        assertEquals("Dairy $tag", two.db.categoryDao().getById("dairy")!!.name)
        assertEquals(0, two.dirty())

        // Different fields on two devices both survive.
        one.edit(milk, name = "Oat milk")
        two.edit(milk, notes = "the blue carton")
        one.run()
        val merged = two.run()
        one.run()
        assertEquals(0, merged.conflicts)
        for (d in listOf(one, two)) {
            assertEquals("Oat milk", d.item(milk)!!.name)
            assertEquals("the blue carton", d.item(milk)!!.notes)
        }

        // The same field on two devices is a conflict; the device that syncs last wins.
        one.edit(milk, name = "From one")
        two.edit(milk, name = "From two")
        one.run()
        val clash = two.run()
        one.run()
        assertEquals(1, clash.conflicts)
        assertEquals("From two", one.item(milk)!!.name)
        assertEquals("From two", two.item(milk)!!.name)

        // Delete and undo travel.
        one.repo.deleteItem(bread)
        one.run()
        two.run()
        assertNotNull(two.item(bread)!!.deletedAt)
        one.repo.restoreItem(bread)
        one.run()
        two.run()
        assertNull(two.item(bread)!!.deletedAt)
        assertEquals(0, one.dirty())
        assertEquals(0, two.dirty())
    }

    @Test
    fun anExpiredAccessTokenIsRefreshedWithTheRealRotationAndSyncContinues() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val d = device("R$tag").signedIn()
        val listId = d.repo.createList("Refresh check $tag")
        assertEquals(SyncOutcome.COMPLETED, d.run().outcome)

        d.clock.now += 20 * 60_000 // past the 15 minute access token
        Thread.sleep(AUTH_SPACING_MS)
        val refreshed = runBlocking { d.session.ensureAccessToken() }

        assertTrue("the refresh token was accepted and rotated: $refreshed (${d.session.lastRefreshProblem})", refreshed is RefreshResult.Token)
        d.repo.renameList(listId, "Refresh check $tag renamed")
        assertEquals(SyncOutcome.COMPLETED, d.run().outcome)
        assertEquals(0, d.dirty())
    }

    @Test
    fun guestListsMoveIntoTheRealAccountAndAppearOnAnotherDevice() = runBlocking {
        val tag = UUID.randomUUID().toString().take(6)
        val guest = device("G$tag") // signed out: new lists are guest lists
        val listId = guest.repo.createList("Guest $tag")
        val eggs = guest.repo.addItem(listId, "12 eggs")!!
        val kept = guest.repo.createList("Stays local $tag")
        guest.signedIn().activeAccount = null // signed in, but rows made from here on are still guest rows

        val migration = MigrationRepository(guest.db, guest.session, guest.engine)
        assertTrue(migration.planBlocking().lists.any { it.id == listId })
        val result = runBlocking { migration.migrate(setOf(listId)) }

        assertEquals(SyncOutcome.COMPLETED, result.outcome)
        assertNull("the list that was not selected is still a guest list", guest.list(kept)!!.accountId)

        val other = device("O$tag").signedIn()
        assertEquals(SyncOutcome.COMPLETED, other.run().outcome)
        assertEquals("Guest $tag", other.list(listId)!!.name)
        assertEquals("eggs", other.item(eggs)!!.name)
        assertEquals(12.0, other.item(eggs)!!.quantity!!, 0.0)
        assertNull("only the selected list reached the account", other.list(kept))
    }

    private fun MigrationRepository.planBlocking() = runBlocking { plan() }

    private companion object {
        const val AUTH_SPACING_MS = 7_000L
    }
}

/** One simulated phone talking to a real AppHub app. */
internal class CloudDevice(val name: String, config: AppHubConfig) {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()
    val clock = TestClock(System.currentTimeMillis())
    val session: SessionManager
    val engine: SyncEngine
    val repo: LocalShoppingRepository
    var activeAccount: String? = null

    init {
        // The production client configurations, exactly as the app builds them.
        session = SessionManager(appHubRetrofit(config, AppHubHttp.authClient()).create(AppHubAuthApi::class.java), MemStorage(null), clock)
        val tables = appHubRetrofit(config, AppHubHttp.dataClient(session)).create(AppHubTableApi::class.java)
        engine = SyncEngine(db, tables, session, clock, minRequestGapMs = 150)
        repo = LocalShoppingRepository(db, clock, PrefixIds(name), AccountScope { activeAccount })
    }

    /** Runs a sync and fails with the per-row server errors if anything was refused. */
    fun run(trigger: SyncTrigger = SyncTrigger.MANUAL): SyncRunResult {
        val result = runBlocking { engine.run(trigger) }
        check(result.failed == 0) { "run on $name: $result; rows: ${rowErrors()}" }
        return result
    }

    private fun rowErrors(): String = db.openHelper.readableDatabase
        .query("SELECT type, substr(id,1,8), failCount, blocked, lastError FROM sync_entities WHERE lastError IS NOT NULL")
        .use { c -> buildList { while (c.moveToNext()) add("${c.getString(0)} ${c.getString(1)} x${c.getInt(2)} blocked=${c.getInt(3)} ${c.getString(4)}") }.toString() }

    fun dirty(): Int = runBlocking { db.syncDao().observeDirtyCount(activeAccount ?: "").first() }

    fun item(id: String) = runBlocking { db.itemDao().getById(id) }

    fun list(id: String) = runBlocking { db.listDao().getById(id) }

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
}
