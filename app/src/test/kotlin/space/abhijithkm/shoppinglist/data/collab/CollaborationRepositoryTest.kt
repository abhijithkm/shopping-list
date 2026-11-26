package space.abhijithkm.shoppinglist.data.collab

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.data.remote.AppHubConfig
import space.abhijithkm.shoppinglist.data.remote.AppHubTableApi
import space.abhijithkm.shoppinglist.data.remote.appHubRetrofit
import space.abhijithkm.shoppinglist.data.session.AccountInfo
import space.abhijithkm.shoppinglist.data.session.SessionManager
import space.abhijithkm.shoppinglist.data.sync.SyncEngine
import space.abhijithkm.shoppinglist.data.sync.SyncOutcome
import space.abhijithkm.shoppinglist.data.sync.SyncRunResult

/** A request that never arrives must fail the test, not hang the build. */
private fun MockWebServer.next(): RecordedRequest =
    takeRequest(5, TimeUnit.SECONDS) ?: error("no request arrived within 5 s")

/** The sharing actions against a MockWebServer that answers like AppHub with the collaboration triggers installed. */
class CollaborationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: SyncEngine
    private lateinit var session: SessionManager
    private lateinit var repository: CollaborationRepository
    private var signedIn = true
    private var counter = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = appHubRetrofit(AppHubConfig(server.url("/apphub/").toString(), "shoppinglistdev"), OkHttpClient())
        val tables = retrofit.create(AppHubTableApi::class.java)
        engine = mockk { coEvery { run(any(), any()) } returns SyncRunResult(SyncOutcome.COMPLETED) }
        session = mockk { every { account } answers { if (signedIn) AccountInfo("1", "me@example.com") else null } }
        val clock = object : Clock {
            override fun nowMillis() = NOW
        }
        val ids = object : IdGenerator {
            override fun newId() = "id-${++counter}"
        }
        repository = CollaborationRepository(tables, session, clock, ids, engine)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun answer(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    private fun refusal(token: String) = answer(422, """{"ok":false,"error":{"status":422,"message":"$token"}}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body!!.utf8()).jsonObject

    @Test
    fun `looking up a person posts the exact address and returns only a hint`() = runTest {
        answer(201, """{"ok":true,"data":{"id":"id-1","status":"found","display_hint":"A. K."}}""")

        val result = repository.lookupUser("  Ann@Example.com ")

        val lookup = (result as CollabResult.Ok).value
        assertTrue(lookup.found)
        assertEquals("A. K.", lookup.hint)
        assertEquals("id-1", lookup.lookupId)
        val request = server.next()
        assertEquals("POST", request.method)
        assertEquals("/apphub/shoppinglistdev/user_lookups", request.url.encodedPath)
        assertEquals("Ann@Example.com", request.json()["email"]!!.jsonPrimitive.content)
        assertEquals("id-1", request.json()["id"]!!.jsonPrimitive.content)
        assertNull("the address must never be in a URL", request.url.query)
    }

    @Test
    fun `an unknown address is reported as not found`() = runTest {
        answer(201, """{"ok":true,"data":{"id":"id-1","status":"not_found"}}""")
        val lookup = (repository.lookupUser("nobody@example.com") as CollabResult.Ok).value
        assertFalse(lookup.found)
    }

    @Test
    fun `an obviously wrong address never leaves the device`() = runTest {
        for (bad in listOf("", "plain", "a@b", "a b@c.d", "@c.d", "a@@c.d")) {
            assertEquals(CollabResult.Failed(CollabFailure.InvalidInput), repository.lookupUser(bad))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the lookup throttle is reported`() = runTest {
        refusal("sl:too_many_attempts")
        assertEquals(CollabResult.Failed(CollabFailure.TooManyAttempts), repository.lookupUser("a@b.co"))
    }

    @Test
    fun `inviting posts the list and the lookup and then refreshes`() = runTest {
        answer(201, """{"ok":true,"data":{"id":"id-1","status":"pending"}}""")

        val result = repository.invite("list-1", UserLookup("lookup-9", found = true, hint = "A. K."))

        assertEquals(CollabResult.Ok(Unit), result)
        val request = server.next()
        assertEquals("/apphub/shoppinglistdev/list_invitations", request.url.encodedPath)
        assertEquals("list-1", request.json()["list_id"]!!.jsonPrimitive.content)
        assertEquals("lookup-9", request.json()["lookup_id"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { engine.run(any(), any()) }
    }

    @Test
    fun `inviting someone who was not found sends nothing`() = runTest {
        assertEquals(CollabResult.Failed(CollabFailure.NotFound), repository.invite("l", UserLookup("x", found = false, hint = null)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an invitation the server refuses is reported by its token`() = runTest {
        refusal("sl:already_member")
        val result = repository.invite("l", UserLookup("x", found = true, hint = null))
        assertEquals(CollabResult.Failed(CollabFailure.AlreadyMember), result)
        coVerify(exactly = 0) { engine.run(any(), any()) }
    }

    @Test
    fun `a new code is secure random, sent once in the body and never in the url`() = runTest {
        answer(201, """{"ok":true,"data":{"id":"id-1","status":"active","expires_at":"2026-11-17 10:00:00.000"}}""")

        val issued = (repository.generateCode("list-1") as CollabResult.Ok).value

        assertTrue(JoinCode.isValid(issued.code))
        assertTrue("expiry from the server is parsed", (issued.expiresAtMs ?: 0L) > 0L)
        val request = server.next()
        assertEquals("/apphub/shoppinglistdev/join_codes", request.url.encodedPath)
        assertEquals(issued.code, request.json()["code"]!!.jsonPrimitive.content)
        assertEquals("list-1", request.json()["list_id"]!!.jsonPrimitive.content)
        assertFalse(request.url.toString().contains(issued.code))
        assertFalse(issued.toString().contains(issued.code))
    }

    @Test
    fun `a code made for a list someone else owns is refused by the server`() = runTest {
        refusal("sl:forbidden")
        assertEquals(CollabResult.Failed(CollabFailure.Forbidden), repository.generateCode("not-mine"))
    }

    @Test
    fun `previewing a code normalises it and returns the list and the owner`() = runTest {
        answer(201, """{"ok":true,"data":{"id":"id-1","status":"found","list_name":"Party","inviter_name":"Ann"}}""")

        val preview = (repository.previewCode(" abcd-2345 ") as CollabResult.Ok).value

        assertEquals("Party", preview.listName)
        assertEquals("Ann", preview.inviterName)
        val request = server.next()
        assertEquals("/apphub/shoppinglistdev/code_lookups", request.url.encodedPath)
        assertEquals("ABCD2345", request.json()["code"]!!.jsonPrimitive.content)
        assertNull(request.url.query)
    }

    @Test
    fun `a malformed code is rejected on the device and costs no attempt`() = runTest {
        assertEquals(CollabResult.Failed(CollabFailure.InvalidCode), repository.previewCode("ABC"))
        assertEquals(CollabResult.Failed(CollabFailure.InvalidCode), repository.previewCode("ABCD234O"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unknown or used code looks the same as any other invalid code`() = runTest {
        answer(201, """{"ok":true,"data":{"id":"id-1","status":"invalid"}}""")
        assertEquals(CollabResult.Failed(CollabFailure.InvalidCode), repository.previewCode("ABCD2345"))
    }

    @Test
    fun `too many wrong codes are throttled`() = runTest {
        refusal("sl:too_many_attempts")
        assertEquals(CollabResult.Failed(CollabFailure.TooManyAttempts), repository.previewCode("ABCD2345"))
    }

    @Test
    fun `asking to join posts the lookup and refreshes`() = runTest {
        answer(201, """{"ok":true,"data":{"id":"id-2","status":"pending"}}""")

        val result = repository.requestAccess(CodePreview("lookup-3", "Party", "Ann"))

        assertEquals(CollabResult.Ok(Unit), result)
        val request = server.next()
        assertEquals("/apphub/shoppinglistdev/join_requests", request.url.encodedPath)
        assertEquals("lookup-3", request.json()["lookup_id"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { engine.run(any(), any()) }
    }

    @Test
    fun `a command carries its name and target and refreshes on success`() = runTest {
        answer(201, """{"ok":true,"data":{"id":"id-1"}}""")

        val result = repository.send(CollabCommand.ApproveJoin, "request-7")

        assertEquals(CollabResult.Ok(Unit), result)
        val request = server.next()
        assertEquals("/apphub/shoppinglistdev/list_commands", request.url.encodedPath)
        assertEquals("approve_join", request.json()["command"]!!.jsonPrimitive.content)
        assertEquals("request-7", request.json()["target_id"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { engine.run(any(), any()) }
    }

    @Test
    fun `every command has a distinct wire name`() {
        val names = CollabCommand.entries.map { it.wire }
        assertEquals(names.size, names.toSet().size)
        assertEquals(
            setOf("accept_invite", "reject_invite", "revoke_invite", "approve_join", "reject_join", "cancel_request", "revoke_code", "remove_member", "leave_list"),
            names.toSet(),
        )
    }

    @Test
    fun `a command that finds the state already changed refreshes so the screen catches up`() = runTest {
        refusal("sl:not_pending")

        val result = repository.send(CollabCommand.AcceptInvite, "inv-1")

        assertEquals(CollabResult.Failed(CollabFailure.NotPending), result)
        coVerify(exactly = 1) { engine.run(any(), any()) }
    }

    @Test
    fun `a command the server forbids does not refresh`() = runTest {
        refusal("sl:forbidden")
        assertEquals(CollabResult.Failed(CollabFailure.Forbidden), repository.send(CollabCommand.RemoveMember, "m-1"))
        coVerify(exactly = 0) { engine.run(any(), any()) }
    }

    @Test
    fun `a server without the collaboration tables says so`() = runTest {
        answer(404, """{"ok":false,"error":{"status":404,"message":"Not found"}}""")
        assertEquals(CollabResult.Failed(CollabFailure.NotAvailable), repository.send(CollabCommand.LeaveList, "m-1"))
    }

    @Test
    fun `no connection is reported as offline`() = runTest {
        server.close()
        assertEquals(CollabResult.Failed(CollabFailure.Offline), repository.send(CollabCommand.LeaveList, "m-1"))
    }

    @Test
    fun `a guest cannot share and nothing is sent`() = runTest {
        signedIn = false
        assertEquals(CollabResult.Failed(CollabFailure.SignInNeeded), repository.lookupUser("a@b.co"))
        assertEquals(CollabResult.Failed(CollabFailure.SignInNeeded), repository.generateCode("l"))
        assertEquals(CollabResult.Failed(CollabFailure.SignInNeeded), repository.send(CollabCommand.AcceptInvite, "x"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the live code of a list is the newest active unexpired one`() = runTest {
        answer(
            200,
            """{"ok":true,"data":[
                {"id":"old","list_id":"list-1","status":"superseded","expires_at":"2026-11-17 09:00:00.000"},
                {"id":"other","list_id":"list-2","status":"active","expires_at":"2099-01-01 00:00:00.000"},
                {"id":"expired","list_id":"list-1","status":"active","expires_at":"2000-01-01 00:00:00.000"},
                {"id":"live","list_id":"list-1","status":"active","expires_at":"2099-01-01 00:00:00.000"}
            ],"meta":{"page":1,"limit":50,"total":4,"pages":1}}""",
        )

        val active = (repository.activeCode("list-1") as CollabResult.Ok).value

        assertEquals("live", active?.id)
        assertEquals("/apphub/shoppinglistdev/join_codes", server.next().url.encodedPath)
    }

    @Test
    fun `a list without a live code has none`() = runTest {
        answer(200, """{"ok":true,"data":[],"meta":{"page":1,"limit":50,"total":0,"pages":0}}""")
        assertNull((repository.activeCode("list-1") as CollabResult.Ok).value)
    }

    private companion object {
        /** 2026-11-16 12:00:00 UTC. */
        const val NOW = 1_794_830_400_000L
    }
}
