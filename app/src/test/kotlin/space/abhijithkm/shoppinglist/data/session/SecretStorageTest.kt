package space.abhijithkm.shoppinglist.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecretStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun cipher(key: SecretKey = newKey()) = AesGcmSecretCipher { key }

    private fun TestScope.newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "session.preferences_pb") }

    @Test
    fun encryptThenDecryptRoundTrips() {
        val c = cipher()
        assertEquals("app.Zx9-refresh_token", c.decrypt(c.encrypt("app.Zx9-refresh_token")))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val c = cipher()
        assertNotEquals(c.encrypt("same"), c.encrypt("same"))
    }

    @Test
    fun theCiphertextDoesNotContainThePlaintext() {
        assertFalse(cipher().encrypt("super-secret-token").contains("super-secret"))
    }

    @Test
    fun aTamperedValueIsRejected() {
        val c = cipher()
        val encoded = c.encrypt("token")
        val flipped = encoded.substring(0, 20) + (if (encoded[20] == 'A') 'B' else 'A') + encoded.substring(21)
        try {
            c.decrypt(flipped)
            fail("tampered ciphertext must not decrypt")
        } catch (e: SecretCipherException) {
            // expected: GCM authentication failed
        }
    }

    @Test
    fun aDifferentKeyCannotDecrypt() {
        val encoded = cipher().encrypt("token")
        try {
            cipher().decrypt(encoded)
            fail("a different key must not decrypt")
        } catch (e: SecretCipherException) {
            // expected
        }
    }

    @Test
    fun garbageAndTruncatedInputAreRejectedNotCrashed() {
        val c = cipher()
        for (bad in listOf("", "not base64 !!!", "AAAA")) {
            try {
                c.decrypt(bad)
                fail("'$bad' must not decrypt")
            } catch (e: SecretCipherException) {
                // expected
            }
        }
    }

    @Test
    fun savedSessionLoadsBackAndTheTokenIsEncryptedOnDisk() = runTest {
        val key = newKey()
        val storage = DataStoreSessionStorage(newStore(), cipher(key))
        val session = StoredSession(AccountInfo("42", "ann@example.com", "Ann"), "app.refresh-token-123")

        storage.save(session)

        assertEquals(session, storage.load())
        val onDisk = File(tmp.root, "session.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("the refresh token must not be readable in the file", onDisk.contains("refresh-token-123"))
        assertTrue("the identity is stored in clear", onDisk.contains("ann@example.com"))
    }

    @Test
    fun aTokenThatCannotBeDecryptedLoadsAsNeedsSignIn() = runTest {
        val store = newStore()
        DataStoreSessionStorage(store, cipher()).save(StoredSession(AccountInfo("42", "ann@example.com"), "tok"))

        // Same stored data, but the key is gone (a new key): the session must degrade, not crash.
        val loaded = DataStoreSessionStorage(store, cipher()).load()

        assertEquals("ann@example.com", loaded?.account?.email)
        assertNull(loaded?.refreshToken)
    }

    @Test
    fun nothingStoredLoadsAsNull() = runTest {
        assertNull(DataStoreSessionStorage(newStore(), cipher()).load())
    }

    @Test
    fun storedSessionNeverPrintsTheToken() {
        assertFalse(StoredSession(AccountInfo("1", "a@b.c"), "SECRET-TOKEN").toString().contains("SECRET"))
    }
}
