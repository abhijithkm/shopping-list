package space.abhijithkm.shoppinglist.data.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The refresh-token cipher with the real Android Keystore (the JVM tests use a software key). */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {
    private val alias = "shoppinglist_test_key"

    private fun cipher() = AesGcmSecretCipher(KeystoreKeyProvider(alias))

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aTokenRoundTripsAndIsNotReadableInTheStoredText() {
        val encoded = cipher().encrypt("app.refresh-token-123")

        assertFalse(encoded.contains("refresh-token"))
        assertEquals("app.refresh-token-123", cipher().decrypt(encoded))
    }

    @Test
    fun theKeyCannotBeExported() {
        assertNull("a Keystore key hands out no key material", KeystoreKeyProvider(alias).invoke().encoded)
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        assertNotEquals(cipher().encrypt("same"), cipher().encrypt("same"))
    }

    @Test
    fun aLostKeyMakesStoredValuesUnreadableInsteadOfCrashing() {
        val encoded = cipher().encrypt("token")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        try {
            cipher().decrypt(encoded) // a new key is created on demand; it cannot open the old value
            fail("a value written with a lost key must not decrypt")
        } catch (e: SecretCipherException) {
            // expected: the session is treated as needing a new sign-in, nothing else is affected
        }
    }
}
