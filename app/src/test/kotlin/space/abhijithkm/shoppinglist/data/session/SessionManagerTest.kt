package space.abhijithkm.shoppinglist.data.session

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.remote.AppHubAuthApi
import space.abhijithkm.shoppinglist.data.remote.AppHubConfig
import space.abhijithkm.shoppinglist.data.remote.AppHubException
import space.abhijithkm.shoppinglist.data.remote.AppHubTableApi
import space.abhijithkm.shoppinglist.data.remote.SessionAuthInterceptor
import space.abhijithkm.shoppinglist.data.remote.SessionAuthenticator
import space.abhijithkm.shoppinglist.data.remote.Tables
import space.abhijithkm.shoppinglist.data.remote.appHubCall
import space.abhijithkm.shoppinglist.data.remote.appHubRetrofit

/** A request that never arrives must fail the test, not hang the build. */
private fun MockWebServer.next(): RecordedRequest =
    takeRequest(5, TimeUnit.SECONDS) ?: error("no request arrived within 5 s")

private class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMillis() = now
}

private class FakeSessionStorage(var stored: StoredSession? = null) : SessionStorage {
    var beforeSave: (StoredSession) -> Unit = {}
    var saves = 0
    var cleared = false
    var failSaves = false
    var loadFailure: Throwable? = null

    override suspend fun load(): StoredSession? {
        loadFailure?.let { throw it }
        return stored
    }

    override suspend fun save(session: StoredSession) {
        if (failSaves) throw java.io.IOException("disk full")
        beforeSave(session)
        saves++
        stored = session
    }

    override suspend fun clear() {
        cleared = true
        stored = null
    }
}

class SessionManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var config: AppHubConfig
    private lateinit var plainClient: OkHttpClient
    private lateinit var auth: AppHubAuthApi
    private val clock = FakeClock()
    private val ann = AccountInfo("42", "ann@example.com", "Ann")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = AppHubConfig(server.url("/apphub/").toString(), "shoppinglistdev")
        plainClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        auth = appHubRetrofit(config, plainClient).create(AppHubAuthApi::class.java)
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

    private fun pair(access: String, refresh: String, expiresIn: Int = 900) =
        """{"ok":true,"data":{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn}}"""

    private fun manager(storage: SessionStorage) = SessionManager(auth, storage, clock)

    private fun signedInStorage(refresh: String = "r0") = FakeSessionStorage(StoredSession(ann, refresh))

    // ---- restore ----------------------------------------------------------------------------------

    @Test
    fun noStoredSessionMeansSignedOut() = runTest {
        val session = manager(FakeSessionStorage())

        session.restore()

        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun storedTokenRestoresSignedInWithoutNetworkAndWithoutAnAccessToken() = runTest {
        val session = manager(signedInStorage())

        session.restore()

        assertEquals(SessionState.SignedIn(ann), session.state.value)
        assertNull(session.currentAccessToken())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun identityWithoutTokenNeedsReauth() = runTest {
        val session = manager(FakeSessionStorage(StoredSession(ann, null)))

        session.restore()

        assertEquals(SessionState.NeedsReauth(ann), session.state.value)
        assertEquals(RefreshResult.SessionEnded, session.ensureAccessToken())
    }

    // ---- sign in / out ----------------------------------------------------------------------------

    @Test
    fun signInStoresTheRefreshTokenAndKeepsTheAccessTokenInMemory() = runTest {
        json(
            200,
            """{"ok":true,"data":{"access_token":"a1","refresh_token":"r1","expires_in":900,
               "user":{"id":42,"email":"ann@example.com","display_name":"Ann"}}}""",
        )
        val storage = FakeSessionStorage()
        val session = manager(storage)

        val account = session.signIn(" ann@example.com ", "pw")

        assertEquals(ann, account)
        assertEquals(SessionState.SignedIn(ann), session.state.value)
        assertEquals("a1", session.currentAccessToken())
        assertEquals("r1", storage.stored?.refreshToken)
        assertTrue(server.next().body!!.utf8().contains("\"email\":\"ann@example.com\""))
    }

    @Test
    fun wrongPasswordFailsWithServerMessageAndLeavesSignedOut() = runTest {
        json(401, """{"ok":false,"error":{"status":401,"message":"Invalid email or password."}}""")
        val session = manager(FakeSessionStorage())

        val error = runCatching { session.signIn("ann@example.com", "bad") }.exceptionOrNull()

        require(error is AppHubException.Http)
        assertEquals(401, error.status)
        assertEquals("Invalid email or password.", error.message)
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals("a wrong password must not trigger a refresh", 1, server.requestCount)
    }

    @Test
    fun signOutCallsLogoutWithTheRefreshTokenThenClearsEverything() = runTest {
        json(200, """{"ok":true,"data":{}}""")
        val storage = signedInStorage("r0")
        val session = manager(storage)
        session.restore()

        // Real dispatcher: sign-out bounds the server call with a timeout, which virtual test time would fire at once.
        withContext(Dispatchers.Default) { session.signOut() }

        assertTrue(server.next().body!!.utf8().contains("\"refresh_token\":\"r0\""))
        assertEquals(SessionState.SignedOut, session.state.value)
        assertTrue(storage.cleared)
        assertNull(session.currentAccessToken())
    }

    @Test
    fun signOutStillClearsTheDeviceWhenTheServerIsUnreachable() = runTest {
        val storage = signedInStorage("r0")
        val session = manager(storage)
        session.restore()
        server.close()

        withContext(Dispatchers.Default) { session.signOut() }

        assertEquals(SessionState.SignedOut, session.state.value)
        assertTrue(storage.cleared)
    }

    // ---- refresh ----------------------------------------------------------------------------------

    @Test
    fun concurrentRejectionsShareOneRefresh() = runTest {
        json(200, pair("a-new", "r-new"))
        val session = manager(signedInStorage("r0"))
        session.restore()

        val results = (1..5).map { async { session.refreshAfterRejection("a-old") } }.awaitAll()

        assertTrue(results.all { it == RefreshResult.Token("a-new") })
        assertEquals("exactly one refresh request", 1, server.requestCount)
    }

    @Test
    fun theNewRefreshTokenIsStoredBeforeTheNewAccessTokenIsUsable() = runTest {
        json(200, pair("a-new", "r-new"))
        val storage = signedInStorage("r0")
        val session = manager(storage)
        session.restore()
        var accessTokenAtSaveTime: String? = "unset"
        storage.beforeSave = { accessTokenAtSaveTime = session.currentAccessToken() }

        session.refreshAfterRejection(null)

        assertNull("storage was written before the access token was published", accessTokenAtSaveTime)
        assertEquals("r-new", storage.stored?.refreshToken)
        assertEquals("a-new", session.currentAccessToken())
        assertTrue(server.next().body!!.utf8().contains("\"refresh_token\":\"r0\""))
    }

    @Test
    fun aRejectedRefreshTokenEndsTheSessionButKeepsTheIdentityAndStopsCallingTheServer() = runTest {
        json(401, """{"ok":false,"error":{"status":401,"message":"Session expired."}}""")
        val storage = signedInStorage("r0")
        val session = manager(storage)
        session.restore()

        assertEquals(RefreshResult.SessionEnded, session.refreshAfterRejection(null))
        assertEquals(RefreshResult.SessionEnded, session.refreshAfterRejection(null))

        assertEquals(SessionState.NeedsReauth(ann), session.state.value)
        assertNull(storage.stored?.refreshToken)
        assertEquals(ann, storage.stored?.account)
        assertEquals("no second request with a dead token", 1, server.requestCount)
    }

    @Test
    fun aBareProxyRefusalIsNotAnEndedSession() = runTest {
        // A 401 or 403 that is not AppHub's error envelope (a proxy, a firewall) says nothing about the token.
        json(403, "<html>Forbidden by firewall</html>")
        json(401, "Unauthorized")
        val storage = signedInStorage("r0")
        val session = manager(storage)
        session.restore()

        assertEquals(RefreshResult.Unavailable, session.refreshAfterRejection(null))
        assertEquals(RefreshResult.Unavailable, session.refreshAfterRejection(null))

        assertEquals(SessionState.SignedIn(ann), session.state.value)
        assertEquals("r0", storage.stored?.refreshToken)
    }

    @Test
    fun requestsThatCarriedNoTokenShareOneRefresh() = runTest {
        json(200, pair("a-new", "r-new"))
        val session = manager(signedInStorage("r0"))
        session.restore()

        val results = (1..4).map { async { session.refreshAfterRejection(null) } }.awaitAll()

        assertTrue(results.all { it == RefreshResult.Token("a-new") })
        assertEquals("one refresh, not one per failed request", 1, server.requestCount)
    }

    @Test
    fun aFailedWriteOfTheRotatedTokenIsRetriedBeforeTheNextHandout() = runTest {
        json(200, pair("a-new", "r-new"))
        val storage = signedInStorage("r0")
        val session = manager(storage)
        session.restore()
        storage.failSaves = true

        assertEquals(RefreshResult.Token("a-new"), session.refreshAfterRejection(null))
        assertEquals("the disk still has the already-revoked token", "r0", storage.stored?.refreshToken)

        storage.failSaves = false
        session.ensureAccessToken()

        assertEquals("r-new", storage.stored?.refreshToken)
    }

    @Test
    fun aSignInThatCannotBeStoredDoesNotLeaveThePreviousSessionBehind() = runTest {
        json(200, pair("a-b", "r-b"))
        val storage = signedInStorage("r-old") // another account's session is on disk
        val session = manager(storage)
        session.restore()
        storage.failSaves = true

        session.signIn("b@example.com", "pw")

        assertNull("a restart must not bring the old account back", storage.stored)
        assertEquals("b@example.com", session.account?.email)
    }

    @Test
    fun aCancelledFirstRestoreDoesNotLatchSignedOut() = runTest {
        val storage = signedInStorage("r0")
        val session = manager(storage)
        storage.loadFailure = kotlinx.coroutines.CancellationException("worker stopped")

        runCatching { session.restore() }
        assertEquals(SessionState.Loading, session.state.value)

        storage.loadFailure = null
        session.restore()
        assertEquals(SessionState.SignedIn(ann), session.state.value)
    }

    @Test
    fun aStorageReadErrorIsRetriedOnTheNextCall() = runTest {
        val storage = signedInStorage("r0")
        val session = manager(storage)
        storage.loadFailure = java.io.IOException("datastore unavailable")

        session.restore()
        assertEquals(SessionState.SignedOut, session.state.value)

        storage.loadFailure = null
        session.restore()
        assertEquals(SessionState.SignedIn(ann), session.state.value)
    }

    @Test
    fun networkFailureDuringRefreshKeepsTheSessionAndTheToken() = runTest {
        val storage = signedInStorage("r0")
        val session = manager(storage)
        session.restore()
        server.close()

        assertEquals(RefreshResult.Unavailable, session.refreshAfterRejection(null))

        assertEquals(SessionState.SignedIn(ann), session.state.value)
        assertEquals("r0", storage.stored?.refreshToken)
    }

    @Test
    fun rateLimitAndServerErrorsKeepTheSession() = runTest {
        json(429, """{"ok":false,"error":{"status":429,"message":"Slow down."}}""", "Retry-After" to "3")
        json(503, """{"ok":false,"error":{"status":503,"message":"Maintenance."}}""")
        val session = manager(signedInStorage("r0"))
        session.restore()

        assertEquals(RefreshResult.Unavailable, session.refreshAfterRejection(null))
        assertEquals(RefreshResult.Unavailable, session.refreshAfterRejection(null))

        assertEquals(SessionState.SignedIn(ann), session.state.value)
    }

    @Test
    fun ensureAccessTokenReusesAFreshTokenAndRefreshesAnExpiringOne() = runTest {
        json(200, pair("a1", "r1", expiresIn = 900))
        json(200, pair("a2", "r2", expiresIn = 900))
        val session = manager(signedInStorage("r0"))
        session.restore()

        assertEquals(RefreshResult.Token("a1"), session.ensureAccessToken())
        assertEquals(RefreshResult.Token("a1"), session.ensureAccessToken())
        assertEquals(1, server.requestCount)

        clock.now += 900_000L - 30_000L // 30 s before expiry: inside the 60 s safety margin
        assertEquals(RefreshResult.Token("a2"), session.ensureAccessToken())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun signedOutSessionNeverRefreshes() = runTest {
        val session = manager(FakeSessionStorage())

        assertEquals(RefreshResult.NotSignedIn, session.ensureAccessToken())
        assertEquals(0, server.requestCount)
    }

    // ---- the OkHttp pair (interceptor + authenticator) -------------------------------------------

    private fun signedInTables(session: SessionManager): AppHubTableApi {
        val client = plainClient.newBuilder()
            .addInterceptor(SessionAuthInterceptor(session))
            .authenticator(SessionAuthenticator(session))
            .build()
        return appHubRetrofit(config, client).create(AppHubTableApi::class.java)
    }

    @Test
    fun a401IsRefreshedOnceAndTheRequestIsRepeatedWithTheNewToken() = runTest {
        json(401, """{"ok":false,"error":{"status":401,"message":"Token expired."}}""")
        json(200, pair("a-new", "r-new"))
        json(200, """{"ok":true,"data":[],"meta":{"page":1,"limit":100,"total":0,"pages":0}}""")
        val session = manager(signedInStorage("r0"))
        session.restore()

        val rows = appHubCall { signedInTables(session).list(Tables.LISTS) }

        assertTrue(rows.isEmpty())
        val first = server.next()
        val refresh = server.next()
        val retry = server.next()
        assertNull("no token yet after a restart", first.headers["Authorization"])
        assertEquals("/apphub/shoppinglistdev/auth/refresh", refresh.url.encodedPath)
        assertEquals("/apphub/shoppinglistdev/shopping_lists", retry.url.encodedPath)
        assertEquals("Bearer a-new", retry.headers["Authorization"])
    }

    @Test
    fun aSecond401AfterTheRetryIsFinalAndDoesNotLoop() = runTest {
        json(401, """{"ok":false,"error":{"status":401,"message":"Token expired."}}""")
        json(200, pair("a-new", "r-new"))
        json(401, """{"ok":false,"error":{"status":401,"message":"Still no."}}""")
        val session = manager(signedInStorage("r0"))
        session.restore()

        val error = runCatching { appHubCall { signedInTables(session).list(Tables.LISTS) } }.exceptionOrNull()

        require(error is AppHubException.Http)
        assertTrue(error.isUnauthorized)
        assertEquals("original, refresh, one retry", 3, server.requestCount)
        assertEquals("the refreshed token is kept", SessionState.SignedIn(ann), session.state.value)
        assertNotNull(session.currentAccessToken())
    }

    @Test
    fun aDeadRefreshTokenSurfacesThe401AndTheNeedsReauthState() = runTest {
        json(401, """{"ok":false,"error":{"status":401,"message":"Token expired."}}""")
        json(401, """{"ok":false,"error":{"status":401,"message":"Session expired."}}""")
        val session = manager(signedInStorage("r0"))
        session.restore()

        val error = runCatching { appHubCall { signedInTables(session).list(Tables.LISTS) } }.exceptionOrNull()

        require(error is AppHubException.Http)
        assertEquals(401, error.status)
        assertEquals(SessionState.NeedsReauth(ann), session.state.value)
    }
}
