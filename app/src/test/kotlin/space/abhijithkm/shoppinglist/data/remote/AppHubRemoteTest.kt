package space.abhijithkm.shoppinglist.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** A request that never arrives must fail the test, not hang the build. */
private fun MockWebServer.next(): RecordedRequest =
    takeRequest(5, TimeUnit.SECONDS) ?: error("no request arrived within 5 s")

/** Wire-level tests of the AppHub client against a MockWebServer that answers like the real envelope. */
class AppHubRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: AppHubAuthApi
    private lateinit var tables: AppHubTableApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = AppHubConfig(server.url("/apphub/").toString(), "shoppinglistdev")
        val retrofit = appHubRetrofit(config, OkHttpClient())
        auth = retrofit.create(AppHubAuthApi::class.java)
        tables = retrofit.create(AppHubTableApi::class.java)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    @Test
    fun appUrlIsNormalised() {
        assertEquals("https://h/apphub/app/", AppHubConfig("https://h/apphub", "app").appUrl)
        assertEquals("https://h/apphub/app/", AppHubConfig("https://h/apphub/", "/app/").appUrl)
    }

    @Test
    fun loginSendsCredentialsAndDecodesTokenPairWithNumericUserId() = runTest {
        json(
            200,
            """{"ok":true,"data":{"access_token":"acc.1","refresh_token":"app.ref1","expires_in":900,
               "token_type":"Bearer","user":{"id":42,"email":"a@b.c","role":"user","display_name":"Ann","extra":1}}}""",
        )

        val pair = appHubCall { auth.login(LoginRequest("a@b.c", "pw")) }

        assertEquals("acc.1", pair.accessToken)
        assertEquals("app.ref1", pair.refreshToken)
        assertEquals(900L, pair.expiresIn)
        assertEquals("42", pair.user?.id)
        assertEquals("Ann", pair.user?.displayName)
        val request = server.next()
        assertEquals("POST", request.method)
        assertEquals("/apphub/shoppinglistdev/auth/login", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"email\":\"a@b.c\""))
        assertTrue(body.contains("\"password\":\"pw\""))
    }

    @Test
    fun registerSendsDisplayNameOnlyWhenGiven() = runTest {
        json(201, """{"ok":true,"data":{"user":{"id":"7","email":"a@b.c"},"verification_email_sent":false}}""")
        json(201, """{"ok":true,"data":{"user":{"id":"8","email":"c@d.e"}}}""")

        appHubCall { auth.register(RegisterRequest("a@b.c", "pw", "Ann")) }
        appHubCall { auth.register(RegisterRequest("c@d.e", "pw")) }

        assertTrue(server.next().body!!.utf8().contains("\"display_name\":\"Ann\""))
        assertFalse(server.next().body!!.utf8().contains("display_name"))
    }

    @Test
    fun refreshSendsTheTokenAndTokenPairNeverPrintsTokens() = runTest {
        json(200, """{"ok":true,"data":{"access_token":"SECRET-A","refresh_token":"SECRET-R","expires_in":900}}""")

        val pair = appHubCall { auth.refresh(RefreshRequest("old.token")) }

        assertTrue(server.next().body!!.utf8().contains("\"refresh_token\":\"old.token\""))
        assertFalse(pair.toString().contains("SECRET"))
    }

    @Test
    fun errorEnvelopeBecomesHttpExceptionWithServerMessageAndReason() = runTest {
        json(
            403,
            """{"ok":false,"error":{"status":403,"message":"Please confirm your email first.",
               "details":{"reason":"email_not_verified"},"request_id":"req-9"}}""",
        )

        val error = runCatching { appHubCall { auth.login(LoginRequest("a@b.c", "pw")) } }.exceptionOrNull()

        require(error is AppHubException.Http)
        assertEquals(403, error.status)
        assertEquals("Please confirm your email first.", error.message)
        assertEquals("email_not_verified", error.reason)
        assertEquals("req-9", error.requestId)
        assertFalse(error.isRetryable)
    }

    @Test
    fun rateLimitCarriesRetryAfter() = runTest {
        json(429, """{"ok":false,"error":{"status":429,"message":"Too many requests."}}""", "Retry-After" to "7")

        val error = runCatching { appHubCall { tables.list(Tables.LISTS) } }.exceptionOrNull()

        require(error is AppHubException.Http)
        assertTrue(error.isRateLimited)
        assertTrue(error.isRetryable)
        assertEquals(7L, error.retryAfterSeconds)
    }

    @Test
    fun errorWithoutEnvelopeStillMapsToHttpException() = runTest {
        json(502, "<html>Bad gateway</html>")

        val error = runCatching { appHubCall { tables.list(Tables.LISTS) } }.exceptionOrNull()

        require(error is AppHubException.Http)
        assertEquals(502, error.status)
        assertTrue(error.isServerError)
        assertTrue(error.isRetryable)
    }

    @Test
    fun unreachableServerBecomesNetworkException() = runTest {
        server.close()

        val error = runCatching { appHubCall { tables.list(Tables.LISTS) } }.exceptionOrNull()

        assertTrue("was $error", error is AppHubException.Network)
    }

    @Test
    fun okFalseOrMissingDataOrGarbageIsAProtocolError() = runTest {
        json(200, """{"ok":false,"error":{"status":200,"message":"odd"}}""")
        json(200, """{"ok":true}""")
        json(200, "not json at all")

        repeat(3) {
            val error = runCatching { appHubCall { tables.get(Tables.LISTS, "x") } }.exceptionOrNull()
            assertTrue("call $it was $error", error is AppHubException.Protocol)
        }
    }

    @Test
    fun theAuthClientUsesAFreshConnectionPerCallDoesNotRetryAndDoesNotFollowRedirects() = runTest {
        json(200, """{"ok":true,"data":{}}""")
        val client = AppHubHttp.authClient()
        val api = appHubRetrofit(AppHubConfig(server.url("/apphub/").toString(), "shoppinglistdev"), client).create(AppHubAuthApi::class.java)

        appHubCallUnit { api.logout(RefreshRequest("t")) }

        assertEquals("close", server.next().headers["Connection"])
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun theDataClientRetriesDroppedConnectionsKeepsItsOwnDispatcherAndDoesNotFollowRedirects() {
        val session = io.mockk.mockk<space.abhijithkm.shoppinglist.data.session.SessionManager>(relaxed = true)
        val a = AppHubHttp.dataClient(session)
        val b = AppHubHttp.dataClient(session)

        assertTrue(a.retryOnConnectionFailure)
        assertFalse(a.followRedirects)
        assertFalse(a.followSslRedirects)
        assertTrue("each client has its own dispatcher", a.dispatcher !== b.dispatcher)
        assertTrue(a.authenticator !== okhttp3.Authenticator.NONE)
    }

    @Test
    fun anUnreadableAuthAnswerNeverQuotesItsBody() = runTest {
        json(200, """{"ok":true,"data":{"access_token":"SECRET-ACCESS","refresh_token":12345,"expires_in":"soon"}}""")

        val error = runCatching { appHubCall { auth.login(LoginRequest("a@b.c", "pw")) } }.exceptionOrNull()

        require(error is AppHubException.Protocol)
        assertNull("no cause: its message would quote the body", error.cause)
        assertFalse(error.message.orEmpty().contains("SECRET"))
    }

    @Test
    fun requestsThatCarrySecretsPrintWithoutThem() {
        assertFalse(LoginRequest("a@b.c", "hunter22").toString().contains("hunter22"))
        assertFalse(RegisterRequest("a@b.c", "hunter22").toString().contains("hunter22"))
        assertFalse(RefreshRequest("app.refresh-secret").toString().contains("refresh-secret"))
        assertFalse(ChangePasswordRequest("old-secret", "new-secret").toString().contains("secret"))
    }

    @Test
    fun listRequestUsesTheDocumentedQueryAndReturnsMeta() = runTest {
        json(
            200,
            """{"ok":true,"data":[{"id":"a","name":"A","updated_at":"2026-11-16 10:00:00.123"}],
               "meta":{"page":1,"limit":100,"total":1,"pages":1}}""",
        )

        val envelope = appHubEnvelope { tables.list(Tables.LISTS) }

        assertEquals(1, envelope.data?.size)
        assertEquals(1, envelope.meta?.pages)
        val url = server.next().url
        assertEquals("/apphub/shoppinglistdev/shopping_lists", url.encodedPath)
        assertEquals("updated_at", url.queryParameter("sort"))
        assertEquals("desc", url.queryParameter("order"))
        assertEquals("100", url.queryParameter("limit"))
        assertEquals("1", url.queryParameter("page"))
    }

    @Test
    fun duplicateCreateIsAConflict() = runTest {
        json(409, """{"ok":false,"error":{"status":409,"message":"A row with this value already exists."}}""")

        val error = runCatching {
            appHubCall { tables.create(Tables.LISTS, buildJsonObject { put("id", "abc") }) }
        }.exceptionOrNull()

        require(error is AppHubException.Http)
        assertTrue(error.isConflict)
    }

    @Test
    fun patchSendsExactlyTheGivenFieldsIncludingExplicitNull() = runTest {
        json(200, """{"ok":true,"data":{"id":"abc","name":"New"}}""")

        appHubCall {
            tables.patch(
                Tables.ITEMS,
                "abc",
                JsonObject(mapOf("name" to JsonPrimitive("New"), "notes" to JsonNull)),
            )
        }

        val request = server.next()
        assertEquals("PATCH", request.method)
        assertEquals("/apphub/shoppinglistdev/shopping_items/abc", request.url.encodedPath)
        assertEquals("""{"name":"New","notes":null}""", request.body!!.utf8())
    }

    @Test
    fun rowsDecodeDecimalStringsAndZeroOneBooleansAndTolerateUnknownColumns() {
        val row = AppHubJson.parseToJsonElement(
            """{"id":"i1","list_id":"l1","name":"Milk","quantity":"2.500","unit":"l","category_key":"dairy",
               "notes":null,"is_bought":1,"bought_at_ms":1700000000000,"sort_order":1.5,
               "tag_ids":"[\"t1\",\"t2\"]","created_at_ms":1,"deleted_at_ms":null,
               "updated_at":"2026-11-16 10:00:00.000","user_id":5,"surprise":true}""",
        ) as JsonObject

        val item = RowCodec.item(row)

        assertEquals(2.5, item.quantity!!, 0.0)
        assertTrue(item.isBought)
        assertEquals(1.5, item.sortOrder, 0.0)
        assertEquals("""["t1","t2"]""", item.tagIds)
        assertNull(item.notes)
        assertNull(item.deletedAtMs)
        assertEquals("2026-11-16 10:00:00.000", item.updatedAt)
    }

    @Test
    fun malformedRowIsAProtocolError() {
        val row = buildJsonObject { put("name", "no id") }

        try {
            RowCodec.list(row)
            fail("expected a protocol error")
        } catch (e: AppHubException.Protocol) {
            // expected: a row without an id is unusable
        }
    }
}
