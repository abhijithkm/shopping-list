package space.abhijithkm.shoppinglist.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** The stored secret could not be decrypted (key gone, data damaged or tampered with). */
class SecretCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Encrypts short secrets (the refresh token) for storage. Output is a printable string. */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** @throws SecretCipherException when the text cannot be authenticated and decrypted. */
    fun decrypt(encoded: String): String
}

/**
 * AES-256-GCM. The 12-byte IV is generated fresh for every encryption and stored in front of the
 * ciphertext. GCM authenticates, so a damaged or modified value fails loudly instead of decrypting to garbage.
 * [keyProvider] supplies the key: the Android Keystore on a device ([KeystoreKeyProvider]) or a plain
 * in-memory key in JVM tests.
 */
class AesGcmSecretCipher(private val keyProvider: () -> SecretKey) : SecretCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return (cipher.iv + ciphertext).toByteString().base64()
    }

    override fun decrypt(encoded: String): String {
        val bytes: ByteString = encoded.decodeBase64() ?: throw SecretCipherException("Stored secret is not valid base64.")
        if (bytes.size <= IV_BYTES + TAG_BYTES) throw SecretCipherException("Stored secret is too short.")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyProvider(),
                GCMParameterSpec(TAG_BYTES * 8, bytes.substring(0, IV_BYTES).toByteArray()),
            )
            String(cipher.doFinal(bytes.substring(IV_BYTES).toByteArray()), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // Bad tag, wrong or missing key, unrecoverable key: the value can never be read again.
            // Other runtime failures (a Keystore that is momentarily unavailable) are not caught here: they are
            // temporary, so the caller retries instead of concluding that the session is gone.
            throw SecretCipherException("Stored secret could not be decrypted.", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/** A non-exportable AES key in the Android Keystore, created on first use. */
class KeystoreKeyProvider(private val alias: String = DEFAULT_ALIAS) : () -> SecretKey {
    override fun invoke(): SecretKey = synchronized(LOCK) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: createKey()
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "shoppinglist_session_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val LOCK = Any()
    }
}
