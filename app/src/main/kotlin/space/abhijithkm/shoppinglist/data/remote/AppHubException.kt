package space.abhijithkm.shoppinglist.data.remote

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * Every failure of a call to AppHub, mapped so callers decide by type and never parse messages.
 * [message] is safe to show to a person (it is the server's own text, or a fixed sentence).
 */
sealed class AppHubException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The server answered with an error status. */
    class Http(
        val status: Int,
        message: String,
        /** `error.details.reason`, e.g. "email_not_verified", when the server sends one. */
        val reason: String? = null,
        val requestId: String? = null,
        /** `Retry-After` in seconds (429, 503), when present. */
        val retryAfterSeconds: Long? = null,
        /**
         * True when the body was AppHub's error envelope, so the answer really came from AppHub. A bare 401 or 403
         * can come from a proxy or firewall and must not be read as "your session is revoked".
         */
        val fromEnvelope: Boolean = false,
    ) : AppHubException(message) {
        val isUnauthorized get() = status == 401
        val isConflict get() = status == 409
        val isNotFound get() = status == 404
        val isRateLimited get() = status == 429
        val isServerError get() = status >= 500

        /** True when repeating the same request later can succeed without any change by the user. */
        val isRetryable get() = status == 408 || status == 425 || status == 429 || status >= 500
    }

    /**
     * No answer arrived: offline, DNS, timeout, connection reset. For a write the request may or may not
     * have reached the server, so callers must only retry operations that are idempotent.
     */
    class Network(cause: Throwable) : AppHubException("Cannot reach the server. Check the connection.", cause)

    /** The server answered 2xx but not with the envelope or fields the client expects. */
    class Protocol(message: String, cause: Throwable? = null) : AppHubException(message, cause)
}

/**
 * Runs one Retrofit call and returns the unwrapped `data`. Cancellation is never swallowed.
 */
suspend fun <T> appHubCall(block: suspend () -> Envelope<T>): T = appHubEnvelope(block).data
    ?: throw AppHubException.Protocol("The server sent an empty answer.")

/** Like [appHubCall] but for calls whose `data` is not needed (logout, forgot password). */
suspend fun <T> appHubCallUnit(block: suspend () -> Envelope<T>) {
    appHubEnvelope(block)
}

/** Like [appHubCall] but keeps `meta` (paging). */
suspend fun <T> appHubEnvelope(block: suspend () -> Envelope<T>): Envelope<T> = try {
    val envelope = block()
    if (!envelope.ok) {
        throw AppHubException.Protocol(envelope.error?.message ?: "The server reported a failure.")
    }
    envelope
} catch (e: CancellationException) {
    throw e
} catch (e: AppHubException) {
    throw e
} catch (e: HttpException) {
    throw e.toAppHubException()
} catch (e: IOException) {
    throw AppHubException.Network(e)
} catch (e: SerializationException) {
    // The cause is dropped on purpose: its message quotes part of the response body, which for sign-in and refresh
    // answers is token text that must never end up in a crash report.
    throw AppHubException.Protocol("The server answer could not be read.")
}

internal fun HttpException.toAppHubException(): AppHubException.Http {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val error = body?.let { text ->
        runCatching { AppHubJson.decodeFromString(Envelope.serializer(JsonElement.serializer()), text).error }.getOrNull()
    }
    val reason = (error?.details as? JsonObject)?.get("reason")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    val retryAfter = response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()
    return AppHubException.Http(
        status = code(),
        message = error?.message?.takeIf { it.isNotBlank() } ?: "Request failed (${code()}).",
        reason = reason,
        requestId = error?.requestId,
        retryAfterSeconds = retryAfter,
        fromEnvelope = error != null,
    )
}
