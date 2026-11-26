package space.abhijithkm.shoppinglist.data.collab

import java.security.SecureRandom
import java.util.Locale
import space.abhijithkm.shoppinglist.data.remote.AppHubException

/**
 * Why a sharing action did not happen. The server decides every one of these (backend/apphub/collab-contract.md,
 * section 1); the app only turns the server's answer into a sentence. [NotAvailable] means this AppHub app has no
 * collaboration tables (it was not provisioned for sharing), so the feature is simply off there.
 */
enum class CollabFailure {
    Forbidden,
    NotAMember,
    NotFound,
    NotPending,
    Expired,
    InvalidCode,
    InvalidInput,
    AlreadyMember,
    AlreadyPending,
    LimitReached,
    TooManyAttempts,
    Conflict,
    ServerNotReady,
    NotAvailable,
    Offline,
    SignInNeeded,
    RateLimited,
    Unknown,
}

sealed interface CollabResult<out T> {
    data class Ok<T>(val value: T) : CollabResult<T>

    data class Failed(val failure: CollabFailure) : CollabResult<Nothing>
}

/** The `sl:` error tokens a server trigger answers with (HTTP 422, in `error.message`). */
object CollabTokens {
    const val CONFLICT = "sl:conflict"
    private val TOKEN = Regex("""sl:([a-z_]+)""")

    /** The token inside [message] (for example "sl:expired"), or null when the message holds none. */
    fun parse(message: String?): String? = message?.let { TOKEN.find(it)?.value }

    fun failureOf(token: String?): CollabFailure = when (token) {
        "sl:forbidden" -> CollabFailure.Forbidden
        "sl:not_a_member" -> CollabFailure.NotAMember
        "sl:not_found" -> CollabFailure.NotFound
        "sl:not_pending" -> CollabFailure.NotPending
        "sl:expired" -> CollabFailure.Expired
        "sl:invalid_code" -> CollabFailure.InvalidCode
        "sl:already_member" -> CollabFailure.AlreadyMember
        "sl:already_pending" -> CollabFailure.AlreadyPending
        "sl:limit_reached" -> CollabFailure.LimitReached
        "sl:too_many_attempts" -> CollabFailure.TooManyAttempts
        "sl:conflict" -> CollabFailure.Conflict
        "sl:server_not_ready" -> CollabFailure.ServerNotReady
        else -> CollabFailure.Unknown
    }
}

/** True when the server refused an item edit because another member changed the row first (`base_revision` mismatch). */
val AppHubException.Http.isRevisionConflict: Boolean
    get() = status == 422 && CollabTokens.parse(message) == CollabTokens.CONFLICT

internal fun AppHubException.toCollabFailure(): CollabFailure = when (this) {
    is AppHubException.Network -> CollabFailure.Offline
    is AppHubException.Protocol -> CollabFailure.Unknown
    is AppHubException.Http -> when {
        // A table that does not exist answers 404 (or 403 when the role has no rule for it): sharing is not set up here.
        status == 404 || status == 403 -> CollabFailure.NotAvailable
        status == 401 -> CollabFailure.SignInNeeded
        status == 429 -> CollabFailure.RateLimited
        status == 422 -> CollabTokens.failureOf(CollabTokens.parse(message))
        else -> CollabFailure.Unknown
    }
}

/**
 * The 8-character join code of Method 2. The alphabet leaves out 0, O, 1 and I so a code read aloud or from a screen is
 * not misread. A code is generated from [SecureRandom], held in memory only and sent exactly once (the server keeps
 * only a salted hash); it is never logged, stored or put in a URL.
 */
object JoinCode {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8

    /** 32 symbols = exactly 5 random bits each, so no symbol is more likely than another. */
    fun generate(random: SecureRandom = SecureRandom()): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** What a person typed, brought to the canonical form: no spaces or dashes, upper case. */
    fun normalize(input: String): String =
        input.filter { !it.isWhitespace() && it != '-' && it != '_' }.uppercase(Locale.ROOT)

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /** "ABCD-EFGH" for display; the dash is never part of the code. */
    fun display(code: String): String = if (code.length == LENGTH) code.substring(0, 4) + "-" + code.substring(4) else code
}

/** The commands a member can issue (the `list_commands` table). */
enum class CollabCommand(val wire: String) {
    AcceptInvite("accept_invite"),
    RejectInvite("reject_invite"),
    RevokeInvite("revoke_invite"),
    ApproveJoin("approve_join"),
    RejectJoin("reject_join"),
    CancelRequest("cancel_request"),
    RevokeCode("revoke_code"),
    RemoveMember("remove_member"),
    LeaveList("leave_list"),
}

/** The server's answer to "does this exact address have an account?": a masked hint, never the address or the full name. */
data class UserLookup(val lookupId: String, val found: Boolean, val hint: String?)

/** The safe preview of a code: the list name and who owns it, nothing else. */
data class CodePreview(val lookupId: String, val listName: String?, val inviterName: String?)

/** A freshly issued code. It exists only in memory, on the screen that shows it. */
class IssuedCode(val code: String, val expiresAtMs: Long?) {
    override fun toString(): String = "IssuedCode(<hidden>)"
}

/** The owner's current code of a list as the server reports it (without the code itself, which the server does not keep). */
data class ActiveCode(val id: String, val expiresAtMs: Long?)
