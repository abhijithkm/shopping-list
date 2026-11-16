package space.abhijithkm.shoppinglist.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Who is signed in, as shown in the UI. Contains no secret. */
data class AccountInfo(val userId: String, val email: String, val displayName: String? = null)

/**
 * What survives a restart. [refreshToken] is null after the server rejected it: the identity is kept
 * so the UI can say whose account needs signing in again, and all local data stays.
 */
data class StoredSession(val account: AccountInfo, val refreshToken: String?) {
    override fun toString(): String =
        "StoredSession(account=$account, refreshToken=${if (refreshToken == null) "none" else "<hidden>"})"
}

interface SessionStorage {
    /** Null when nothing is stored. A token that can no longer be decrypted comes back as `refreshToken = null`. */
    suspend fun load(): StoredSession?

    suspend fun save(session: StoredSession)

    suspend fun clear()
}

/**
 * DataStore-backed storage. The refresh token is encrypted with [SecretCipher] (Android Keystore AES-GCM on a
 * device); the access token is never stored. If the token cannot be decrypted (key lost) the session comes
 * back without a token, so the user is asked to sign in again, and local data is untouched.
 */
class DataStoreSessionStorage(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : SessionStorage {
    override suspend fun load(): StoredSession? {
        val prefs = store.data.first()
        val userId = prefs[USER_ID] ?: return null
        val email = prefs[EMAIL] ?: return null
        val account = AccountInfo(userId, email, prefs[DISPLAY_NAME])
        val encrypted = prefs[REFRESH_TOKEN] ?: return StoredSession(account, null)
        val token = try {
            cipher.decrypt(encrypted)
        } catch (e: SecretCipherException) {
            return StoredSession(account, null)
        }
        return StoredSession(account, token)
    }

    override suspend fun save(session: StoredSession) {
        val encrypted = session.refreshToken?.let { cipher.encrypt(it) }
        store.edit { prefs ->
            prefs[USER_ID] = session.account.userId
            prefs[EMAIL] = session.account.email
            val name = session.account.displayName
            if (name == null) prefs.remove(DISPLAY_NAME) else prefs[DISPLAY_NAME] = name
            if (encrypted == null) prefs.remove(REFRESH_TOKEN) else prefs[REFRESH_TOKEN] = encrypted
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token_enc")
    }
}
