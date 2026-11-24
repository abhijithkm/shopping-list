package space.abhijithkm.shoppinglist.data.collab

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.data.remote.AppHubException
import space.abhijithkm.shoppinglist.data.remote.AppHubTableApi
import space.abhijithkm.shoppinglist.data.remote.Envelope
import space.abhijithkm.shoppinglist.data.remote.RowCodec
import space.abhijithkm.shoppinglist.data.remote.Tables
import space.abhijithkm.shoppinglist.data.remote.appHubEnvelope
import space.abhijithkm.shoppinglist.data.session.SessionManager
import space.abhijithkm.shoppinglist.data.sync.ServerTimestamp
import space.abhijithkm.shoppinglist.data.sync.SyncEngine
import space.abhijithkm.shoppinglist.data.sync.SyncTrigger

/**
 * The online-only sharing actions (docs/APPHUB_INTEGRATION.md, "Collaboration"). Each action is one INSERT into a
 * server table; the server's triggers authorize it and do the work, and answer 422 with an `sl:` token when it is
 * refused. Nothing here is queued for later: an invitation or approval that cannot reach the server right now simply
 * fails with [CollabFailure.Offline] and the person tries again.
 *
 * Every table name and column is in backend/apphub/collab-contract.md. A join code goes through exactly two calls,
 * [generateCode] and [previewCode]; it is never logged, stored or kept after the call.
 */
@Singleton
class CollaborationRepository @Inject constructor(
    private val tables: AppHubTableApi,
    private val session: SessionManager,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val engine: SyncEngine,
) {
    /** Method 1, step 1: does this exact address belong to an account? The answer is masked; the address is never echoed. */
    suspend fun lookupUser(email: String): CollabResult<UserLookup> {
        val cleaned = email.trim()
        if (!looksLikeEmail(cleaned)) return CollabResult.Failed(CollabFailure.InvalidInput)
        val id = ids.newId()
        return attempt {
            val row = RowCodec.userLookup(create(Tables.USER_LOOKUPS, obj("id" to id, "email" to cleaned)))
            UserLookup(lookupId = row.id.ifBlank { id }, found = row.status == STATUS_FOUND, hint = row.displayHint)
        }
    }

    /** Method 1, step 2: invite the person the lookup found. The server checks everything again. */
    suspend fun invite(listId: String, lookup: UserLookup): CollabResult<Unit> {
        if (!lookup.found) return CollabResult.Failed(CollabFailure.NotFound)
        return attempt {
            create(Tables.INVITATIONS, obj("id" to ids.newId(), "list_id" to listId, "lookup_id" to lookup.lookupId))
            Unit
        }.also { if (it is CollabResult.Ok) refresh() }
    }

    /**
     * Method 2, owner side: creates a new code for the list (older ones stop working). The code is made here with a
     * secure random generator and returned to the caller to show once; the server keeps only a salted hash.
     */
    suspend fun generateCode(listId: String): CollabResult<IssuedCode> {
        val code = JoinCode.generate()
        return attempt {
            val row = create(Tables.JOIN_CODES, obj("id" to ids.newId(), "list_id" to listId, "code" to code))
            IssuedCode(code, expiresAtMs = row.string("expires_at")?.let { ServerTimestamp.parse(it) })
        }
    }

    /** The owner's live code of this list, if any (the server keeps no plaintext, so only its id and expiry). */
    suspend fun activeCode(listId: String): CollabResult<ActiveCode?> = attempt {
        val rows = appHubEnvelope { tables.list(Tables.JOIN_CODES, limit = CODES_TO_READ) }.data.orEmpty()
        rows.filter { it.string("list_id") == listId && it.string("status") == "active" }
            .mapNotNull { row ->
                val id = row.string("id") ?: return@mapNotNull null
                val expires = row.string("expires_at")?.let { ServerTimestamp.parse(it) }
                // The server clock is UTC (provisioning checks it), so "now" on the device is comparable for a display check.
                if (expires != null && expires <= clock.nowMillis()) null else ActiveCode(id, expires)
            }
            .maxByOrNull { it.expiresAtMs ?: Long.MAX_VALUE }
    }

    /** Method 2, joiner side, step 1: what does this code open? Returns the list name and its owner, nothing more. */
    suspend fun previewCode(rawCode: String): CollabResult<CodePreview> {
        val code = JoinCode.normalize(rawCode)
        // A malformed code never leaves the device: it cannot be valid and would only count against the attempt limit.
        if (!JoinCode.isValid(code)) return CollabResult.Failed(CollabFailure.InvalidCode)
        val id = ids.newId()
        return attempt {
            val row = RowCodec.codeLookup(create(Tables.CODE_LOOKUPS, obj("id" to id, "code" to code)))
            if (row.status != STATUS_FOUND) throw InvalidCodeAnswer()
            CodePreview(row.id.ifBlank { id }, row.listName, row.inviterName)
        }
    }

    /** Method 2, joiner side, step 2: ask the owner for access. Uses up the code; the owner decides. */
    suspend fun requestAccess(preview: CodePreview): CollabResult<Unit> = attempt {
        create(Tables.JOIN_REQUESTS, obj("id" to ids.newId(), "lookup_id" to preview.lookupId))
        Unit
    }.also { if (it is CollabResult.Ok) refresh() }

    /** Any of the nine commands (accept, reject, revoke, approve, remove, leave...). The server authorizes it. */
    suspend fun send(command: CollabCommand, targetId: String): CollabResult<Unit> = attempt {
        create(Tables.COMMANDS, obj("id" to ids.newId(), "command" to command.wire, "target_id" to targetId))
        Unit
    }.also {
        // Success changes who may see what, and a refusal (already handled, expired) means the local copy is stale: read again.
        if (it is CollabResult.Ok || (it as? CollabResult.Failed)?.failure in STALE_AFTER) refresh()
    }

    /** One full sync: pulls the membership, invitation and request state and, after a join, the list and its items. */
    suspend fun refresh() {
        engine.run(SyncTrigger.MANUAL)
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private class InvalidCodeAnswer : Exception()

    private suspend fun <T> attempt(block: suspend () -> T): CollabResult<T> {
        if (session.account == null) return CollabResult.Failed(CollabFailure.SignInNeeded)
        return try {
            CollabResult.Ok(block())
        } catch (e: InvalidCodeAnswer) {
            CollabResult.Failed(CollabFailure.InvalidCode)
        } catch (e: AppHubException) {
            CollabResult.Failed(e.toCollabFailure())
        }
    }

    private suspend fun create(table: String, body: JsonObject): JsonObject {
        val envelope: Envelope<JsonObject> = appHubEnvelope { tables.create(table, body) }
        return envelope.data ?: JsonObject(emptyMap())
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun looksLikeEmail(text: String): Boolean {
        if (text.length !in 3..MAX_EMAIL || text.any { it.isWhitespace() }) return false
        val at = text.indexOf('@')
        return at > 0 && at == text.lastIndexOf('@') && at < text.length - 1 && '.' in text.substring(at)
    }

    private companion object {
        const val STATUS_FOUND = "found"
        const val MAX_EMAIL = 254
        const val CODES_TO_READ = 50
        val STALE_AFTER = setOf(CollabFailure.NotPending, CollabFailure.Expired, CollabFailure.NotFound, CollabFailure.NotAMember)
    }
}
