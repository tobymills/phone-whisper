package com.kafkasl.phonewhisper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-private, non-exportable Android Keystore encryption for the AIOS bearer.
 *
 * The token is never committed or placed in URLs, exception messages or logs.
 * Previously saved plaintext settings are migrated on first successful read.
 * Keystore failures fail closed rather than silently falling back to plaintext.
 */
object LocalSecrets {
    private const val PREFS = "phonewhisper"
    private const val KEY = "stream_token_secure"
    private const val OLD_KEY = "stream_token"
    private const val ALIAS = "phonewhisper_aios_token_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /**
     * Encrypt or clear the bearer. Returns false if Android's keystore fails.
     * No secret is stored in normal preferences as a fallback.
     */
    @Synchronized
    fun store(context: Context, value: String): Boolean = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isBlank()) {
            prefs.edit().remove(KEY).remove(OLD_KEY).commit()
        } else {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + ciphertext
            prefs.edit()
                .putString(KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .remove(OLD_KEY)
                .commit()
        }
    } catch (_: Exception) {
        false
    }

    /** Decrypt the bearer only for an outgoing authenticated request. */
    @Synchronized
    fun read(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY, null)
        if (saved.isNullOrEmpty()) {
            val legacy = prefs.getString(OLD_KEY, null) ?: return ""
            return if (store(context, legacy)) legacy else ""
        }
        return try {
            val data = Base64.decode(saved, Base64.NO_WRAP)
            if (data.size < 29) return ""
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, data.copyOfRange(0, 12))
            )
            cipher.doFinal(data.copyOfRange(12, data.size))
                .toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
