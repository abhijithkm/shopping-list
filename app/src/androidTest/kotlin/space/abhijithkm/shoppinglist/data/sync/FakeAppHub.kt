package space.abhijithkm.shoppinglist.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import space.abhijithkm.shoppinglist.data.remote.AppHubJson

/**
 * A small stateful stand-in for AppHub, served by MockWebServer, for sync tests. It models the behaviours the
 * sync engine relies on (see backend/apphub/fake-server for the faithful Node version used end to end):
 * per-user rows, client primary keys with 409 on duplicates, field-level PATCH, `updated_at` bumped only when
 * a value really changes, `sort=updated_at&order=desc` paging with `meta.pages`, bearer tokens, one-time
 * refresh tokens, and injectable faults. Time is virtual: every write advances the server clock by 1 s.
 */
class FakeAppHub {
    class Recorded(val method: String, val path: String, val query: Map<String, String>, val body: JsonObject?)

    private val tables = mutableMapOf<String, MutableMap<String, MutableMap<String, JsonElement>>>() // key: user|table -> id -> row
    private val accessTokens = mutableMapOf<String, String>()
    private val refreshTokens = mutableMapOf<String, String>()
    private var serverNow = ServerTimestamp.parse("2026-11-16 10:00:00.000")!!
    private var tokenCounter = 0

    val requests = mutableListOf<Recorded>()

    /** (method, table) -> status to answer with for every matching request until cleared; 500 mimics a CHECK refusal. */
    val refusals = mutableMapOf<Pair<String, String>, Int>()

    /** Answer the next N data requests with this status (e.g. 429 or 503), whatever they are. */
    var failNext: Pair<Int, Int>? = null
    var rejectAccessTokens = false

    private var server = MockWebServer()
    private var port = 0

    val baseUrl: String get() = server.url("/apphub/").toString()

    fun start() {
        server = MockWebServer().also { it.dispatcher = dispatcher }
        server.start()
        port = server.port
    }

    /** The server stops answering: the client sees a connection failure. */
    fun goOffline() {
        server.close()
    }

    fun goOnline() {
        server = MockWebServer().also { it.dispatcher = dispatcher }
        server.start(port)
    }

    fun shutdown() {
        runCatching { server.close() }
    }

    fun issueTokens(user: String): Pair<String, String> {
        val access = "acc-${++tokenCounter}"
        val refresh = "ref-${++tokenCounter}"
        accessTokens[access] = user
        refreshTokens[refresh] = user
        return access to refresh
    }

    /** Moves the server clock forward, so rows written later have a clearly newer `updated_at`. */
    fun advanceClock(millis: Long) {
        serverNow += millis
    }

    /** Makes every issued refresh token unusable (the session is gone on the server). */
    fun revokeAllRefreshTokens() = refreshTokens.clear()

    fun rows(user: String, table: String): List<Map<String, JsonElement>> =
        tables["$user|$table"]?.values?.map { it.toMap() } ?: emptyList()

    fun row(user: String, table: String, id: String): Map<String, JsonElement>? = tables["$user|$table"]?.get(id)?.toMap()

    /** Inserts or replaces a row directly (as another client would have), bumping `updated_at`. */
    fun put(user: String, table: String, row: JsonObject) {
        val store = tables.getOrPut("$user|$table") { mutableMapOf() }
        val id = row.getValue("id").let { (it as JsonPrimitive).content }
        val stored = store.getOrPut(id) { mutableMapOf() }
        stored.putAll(row)
        stored["updated_at"] = JsonPrimitive(stamp())
    }

    fun count(method: String, table: String) = requests.count { it.method == method && it.path.endsWith("/$table") }

    fun writes() = requests.count { it.method == "POST" || it.method == "PATCH" }

    private fun stamp(): String {
        serverNow += 1000
        return ServerTimestamp.format(serverNow)
    }

    private fun ok(code: Int, data: JsonElement?, meta: JsonElement? = null): MockResponse {
        val body = buildMap<String, JsonElement> {
            put("ok", JsonPrimitive(true))
            if (data != null) put("data", data)
            if (meta != null) put("meta", meta)
        }
        return MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(JsonObject(body).toString()).build()
    }

    private fun error(code: Int, message: String, retryAfter: Int? = null): MockResponse {
        val body = """{"ok":false,"error":{"status":$code,"message":"$message"}}"""
        val b = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
        if (retryAfter != null) b.addHeader("Retry-After", retryAfter.toString())
        return b.build()
    }

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeAppHub) { handle(request) }
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        val query = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
        val bodyText = request.body?.utf8()
        val body = bodyText?.takeIf { it.isNotBlank() }?.let { AppHubJson.parseToJsonElement(it).jsonObject }
        val method = request.method.orEmpty()
        requests += Recorded(method, path, query, body)
        val rest = path.substringAfter("/apphub/").substringAfter('/') // after the app name

        if (rest == "auth/refresh") {
            val old = (body?.get("refresh_token") as? JsonPrimitive)?.content
            val user = refreshTokens.remove(old) ?: return error(401, "The refresh token is not valid.")
            val (a, r) = issueTokens(user)
            return ok(200, JsonObject(mapOf("access_token" to JsonPrimitive(a), "refresh_token" to JsonPrimitive(r), "expires_in" to JsonPrimitive(900))))
        }

        if (rest == "auth/logout") return ok(200, JsonObject(emptyMap()))

        if (rest == "auth/login") {
            // Any password is accepted; the account is the part of the address before the @ ("user-1@example.com" is user-1).
            val email = (body?.get("email") as? JsonPrimitive)?.content ?: return error(422, "email is required.")
            val user = email.substringBefore('@')
            val (a, r) = issueTokens(user)
            val userRow = JsonObject(mapOf("id" to JsonPrimitive(user), "email" to JsonPrimitive(email)))
            return ok(
                200,
                JsonObject(
                    mapOf(
                        "access_token" to JsonPrimitive(a), "refresh_token" to JsonPrimitive(r),
                        "expires_in" to JsonPrimitive(900), "user" to userRow,
                    ),
                ),
            )
        }

        val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")
        val user = bearer?.let { accessTokens[it] }
        if (user == null || rejectAccessTokens) return error(401, "The access token is missing or expired.")

        failNext?.let { (status, count) ->
            if (count > 0) {
                failNext = (status to count - 1).takeIf { count > 1 }
                return error(status, "Injected failure.", retryAfter = if (status == 429) 1 else null)
            }
        }

        val parts = rest.split('/')
        val table = parts[0]
        val id = parts.getOrNull(1)
        refusals[method to table]?.let { return error(it, "The database refused the change.") }
        val store = tables.getOrPut("$user|$table") { mutableMapOf() }

        return when {
            method == "GET" && id == null -> list(store, query)
            method == "GET" -> store[id]?.let { ok(200, JsonObject(it)) } ?: error(404, "Not found.")
            method == "POST" -> create(store, body ?: return error(422, "Body required."))
            method == "PATCH" && id != null -> patch(store, id, body ?: return error(422, "Body required."))
            else -> error(405, "Method not allowed.")
        }
    }

    private fun list(store: Map<String, Map<String, JsonElement>>, query: Map<String, String>): MockResponse {
        val limit = (query["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val sorted = store.values.sortedWith(compareByDescending<Map<String, JsonElement>> { (it["updated_at"] as? JsonPrimitive)?.content.orEmpty() }.thenByDescending { (it["id"] as JsonPrimitive).content })
        val pageRows = sorted.drop((page - 1) * limit).take(limit).map { JsonObject(it) }
        val pages = if (sorted.isEmpty()) 0 else (sorted.size + limit - 1) / limit
        val meta = JsonObject(mapOf("page" to JsonPrimitive(page), "limit" to JsonPrimitive(limit), "total" to JsonPrimitive(sorted.size), "pages" to JsonPrimitive(pages)))
        return ok(200, kotlinx.serialization.json.JsonArray(pageRows), meta)
    }

    private fun create(store: MutableMap<String, MutableMap<String, JsonElement>>, body: JsonObject): MockResponse {
        val id = (body["id"] as? JsonPrimitive)?.content ?: return error(422, "id is required.")
        if (store.containsKey(id)) return error(409, "A row with this value already exists.")
        val row = mutableMapOf<String, JsonElement>()
        row.putAll(body)
        row["updated_at"] = JsonPrimitive(stamp())
        store[id] = row
        return ok(201, JsonObject(row))
    }

    private fun patch(store: MutableMap<String, MutableMap<String, JsonElement>>, id: String, body: JsonObject): MockResponse {
        val row = store[id] ?: return error(404, "Not found.")
        var changed = false
        for ((k, v) in body) {
            if (k == "id" || k == "updated_at" || k == "user_id") return error(422, "These fields cannot be written.")
            val old = row[k] ?: JsonNull
            if (!sameValue(old, v)) {
                row[k] = v
                changed = true
            }
        }
        if (changed) row["updated_at"] = JsonPrimitive(stamp())
        return ok(200, JsonObject(row))
    }

    private fun sameValue(a: JsonElement, b: JsonElement): Boolean {
        val na = (a as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val nb = (b as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return if (na != null && nb != null) na == nb else a == b
    }
}
