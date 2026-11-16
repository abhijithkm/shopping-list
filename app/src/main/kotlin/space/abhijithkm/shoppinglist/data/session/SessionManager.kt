package space.abhijithkm.shoppinglist.data.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.remote.AppHubAuthApi
import space.abhijithkm.shoppinglist.data.remote.AppHubException
import space.abhijithkm.shoppinglist.data.remote.AppHubUser
import space.abhijithkm.shoppinglist.data.remote.ForgotRequest
import space.abhijithkm.shoppinglist.data.remote.LoginRequest
import space.abhijithkm.shoppinglist.data.remote.RefreshRequest
import space.abhijithkm.shoppinglist.data.remote.RegisterRequest
import space.abhijithkm.shoppinglist.data.remote.RegisterResult
import space.abhijithkm.shoppinglist.data.remote.appHubCall
import space.abhijithkm.shoppinglist.data.remote.appHubCallUnit

sealed interface SessionState {
    /** Stored session not read yet (first moments after process start). */
    data object Loading : SessionState

    /** Guest, or signed out. Everything works locally; cloud features ask for sign-in when used. */
    data object SignedOut : SessionState

    data class SignedIn(val account: AccountInfo) : SessionState

    /** The server rejected the refresh token (expired, revoked, reused). Local data is untouched; sign in again. */
    data class NeedsReauth(val account: AccountInfo) : SessionState
}

sealed interface RefreshResult {
    data class Token(val accessToken: String) : RefreshResult {
        override fun toString(): String = "Token(<hidden>)"
    }

    /** Not possible right now (offline, rate limited, server error). The session is kept; try again later. */
    data object Unavailable : RefreshResult

    /** The server rejected the refresh token; the session is over until the user signs in again. */
    data object SessionEnded : RefreshResult

    data object NotSignedIn : RefreshResult
}

/**
 * Owns the sign-in session: the access token (memory only), the one-time refresh token (also persisted,
 * encrypted) and every call that changes them. The rules, all from AppHub's refresh design
 * (docs/APPHUB_INTEGRATION.md):
 *  - one [Mutex] serialises sign-in, sign-out and refresh, so two callers never present the same refresh token;
 *  - a refreshed pair is persisted **before** the new access token is handed out, and the whole refresh runs
 *    non-cancellably, so a rotated token is never lost to a cancelled coroutine;
 *  - a refresh is never repeated inside one call (the HTTP client has no automatic retry for it);
 *  - only a definitive rejection (401 or 403) ends the session; offline, 429 and 5xx keep it.
 */
@Singleton
class SessionManager @Inject constructor(
    private val auth: AppHubAuthApi,
    private val storage: SessionStorage,
    private val clock: Clock,
) {
    private class Tokens(val access: String?, val accessExpiresAtMs: Long, val refresh: String?)

    private val lock = Mutex()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    @Volatile private var tokens: Tokens? = null

    /** A session whose last write to storage failed; retried before the next token is handed out. */
    private var pendingPersist: StoredSession? = null

    /** True when reading the stored session failed: the next call tries again instead of trusting "signed out". */
    private var restoreFailed = false

    /** The signed-in account, or the account that needs to sign in again. */
    val account: AccountInfo?
        get() = when (val s = mutableState.value) {
            is SessionState.SignedIn -> s.account
            is SessionState.NeedsReauth -> s.account
            else -> null
        }

    /** Reads the stored session. Safe to call repeatedly; only the first call has an effect. */
    suspend fun restore() = lock.withLock { restoreLocked() }

    private suspend fun restoreLocked() {
        if (mutableState.value != SessionState.Loading && !restoreFailed) return
        val stored = try {
            storage.load()
        } catch (e: CancellationException) {
            // A cancelled first caller must not leave the process believing nobody is signed in.
            throw e
        } catch (e: Exception) {
            restoreFailed = true
            mutableState.value = SessionState.SignedOut
            return
        }
        restoreFailed = false
        mutableState.value = when {
            stored == null -> SessionState.SignedOut
            stored.refreshToken == null -> SessionState.NeedsReauth(stored.account)
            else -> {
                tokens = Tokens(access = null, accessExpiresAtMs = 0, refresh = stored.refreshToken)
                SessionState.SignedIn(stored.account)
            }
        }
    }

    /** The access token to send, or null when there is none yet (signed out, or not refreshed since start). */
    fun currentAccessToken(): String? = tokens?.access

    /** A usable access token, refreshing first when there is none or it is about to expire. */
    suspend fun ensureAccessToken(): RefreshResult = lock.withLock {
        restoreLocked()
        if (mutableState.value !is SessionState.SignedIn) {
            return@withLock if (mutableState.value is SessionState.NeedsReauth) RefreshResult.SessionEnded else RefreshResult.NotSignedIn
        }
        retryPendingPersist()
        val current = tokens
        if (current?.access != null && clock.nowMillis() < current.accessExpiresAtMs - EXPIRY_SKEW_MS) {
            RefreshResult.Token(current.access)
        } else {
            // The token being replaced is the one that is missing or about to expire.
            refreshLocked(rejected = current?.access)
        }
    }

    /**
     * Called when a request carrying [rejected] (or none) came back 401. If another caller already replaced
     * the token, that one is returned without a network call; otherwise the refresh token is exchanged.
     */
    suspend fun refreshAfterRejection(rejected: String?): RefreshResult = lock.withLock {
        restoreLocked()
        retryPendingPersist()
        refreshLocked(rejected)
    }

    /**
     * [rejected] is the access token that must be replaced (null when the failed request carried none). If the
     * current token is a different one, another caller already refreshed and nothing is sent.
     */
    private suspend fun refreshLocked(rejected: String?): RefreshResult {
        val account = this.account
        val current = tokens
        if (account == null) return RefreshResult.NotSignedIn
        if (mutableState.value is SessionState.NeedsReauth) return RefreshResult.SessionEnded
        if (current?.access != null && current.access != rejected) {
            return RefreshResult.Token(current.access)
        }
        val refreshToken = current?.refresh ?: return RefreshResult.SessionEnded
        // Once the request may have left the device, its answer must be stored even if the caller is cancelled:
        // the server has already revoked the token we sent.
        return withContext(NonCancellable) {
            val pair = try {
                appHubCall { auth.refresh(RefreshRequest(refreshToken)) }
            } catch (e: AppHubException.Http) {
                // Only AppHub's own answer ends the session. A bare 401 or 403 (a proxy, a firewall, an IP block)
                // says nothing about the token, so it is treated like any other temporary failure.
                return@withContext if ((e.status == 401 || e.status == 403) && e.fromEnvelope) {
                    endSessionLocked(account)
                    RefreshResult.SessionEnded
                } else {
                    RefreshResult.Unavailable
                }
            } catch (e: AppHubException) {
                return@withContext RefreshResult.Unavailable
            }
            val updated = pair.user?.toAccount(account) ?: account
            // Persist before use. If the write fails, the session is kept in memory for this process and the write
            // is retried before the next token is handed out; it cannot be made atomic with the server's rotation.
            persist(StoredSession(updated, pair.refreshToken))
            tokens = Tokens(pair.accessToken, clock.nowMillis() + pair.expiresIn * 1000, pair.refreshToken)
            mutableState.value = SessionState.SignedIn(updated)
            RefreshResult.Token(pair.accessToken)
        }
    }

    private suspend fun endSessionLocked(account: AccountInfo) {
        tokens = null
        persist(StoredSession(account, null))
        mutableState.value = SessionState.NeedsReauth(account)
    }

    /** Writes the session. A failure is remembered and retried later; cancellation is never swallowed. */
    private suspend fun persist(session: StoredSession): Boolean = try {
        storage.save(session)
        pendingPersist = null
        true
    } catch (e: CancellationException) {
        pendingPersist = session
        throw e
    } catch (e: Exception) {
        pendingPersist = session
        false
    }

    private suspend fun retryPendingPersist() {
        pendingPersist?.let { persist(it) }
    }

    /** @throws AppHubException wrong credentials (401), unverified email (403, reason `email_not_verified`), rate limit, network. */
    suspend fun signIn(email: String, password: String): AccountInfo = lock.withLock {
        restoreLocked()
        val cleanEmail = email.trim()
        withContext(NonCancellable) {
            val pair = appHubCall { auth.login(LoginRequest(cleanEmail, password)) }
            val account = pair.user?.toAccount(null, cleanEmail) ?: AccountInfo(cleanEmail.lowercase(), cleanEmail)
            // If the new session cannot be stored, make sure a previous account's session is not left behind to
            // come back after a restart.
            if (!persist(StoredSession(account, pair.refreshToken))) runCatching { storage.clear() }
            restoreFailed = false
            tokens = Tokens(pair.accessToken, clock.nowMillis() + pair.expiresIn * 1000, pair.refreshToken)
            mutableState.value = SessionState.SignedIn(account)
            account
        }
    }

    /** Creates the account. AppHub does not sign the user in; call [signIn] afterwards. */
    suspend fun register(email: String, password: String, displayName: String? = null): RegisterResult {
        val name = displayName?.trim()?.take(MAX_DISPLAY_NAME)?.takeIf { it.isNotEmpty() }
        return appHubCall { auth.register(RegisterRequest(email.trim(), password, name)) }
    }

    suspend fun forgotPassword(email: String) {
        appHubCallUnit { auth.forgot(ForgotRequest(email.trim())) }
    }

    /**
     * Ends the session on the server when reachable (best effort, a few seconds) and always on the device.
     * Local lists are not touched here.
     */
    suspend fun signOut() = lock.withLock {
        restoreLocked()
        val refreshToken = tokens?.refresh
        try {
            if (refreshToken != null) {
                withTimeoutOrNull(LOGOUT_TIMEOUT_MS) {
                    runCatching { appHubCallUnit { auth.logout(RefreshRequest(refreshToken)) } }
                }
            }
        } finally {
            withContext(NonCancellable) {
                tokens = null
                pendingPersist = null
                restoreFailed = false
                runCatching { storage.clear() }
                mutableState.value = SessionState.SignedOut
            }
        }
    }

    private fun AppHubUser.toAccount(previous: AccountInfo?, fallbackEmail: String = previous?.email.orEmpty()): AccountInfo =
        AccountInfo(
            userId = id.ifBlank { previous?.userId ?: fallbackEmail.lowercase() },
            email = email.ifBlank { fallbackEmail },
            displayName = displayName ?: previous?.displayName,
        )

    private companion object {
        const val EXPIRY_SKEW_MS = 60_000L
        const val MAX_DISPLAY_NAME = 60
        const val LOGOUT_TIMEOUT_MS = 5_000L
    }
}
