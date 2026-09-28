package com.bvb.android.core.security

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
 * Minimal at-rest encrypted key/value store backed by an Android Keystore
 * AES/GCM key. Replaces the deprecated androidx.security:security-crypto
 * (EncryptedSharedPreferences / MasterKey).
 *
 * Each value is sealed independently as "base64(iv):base64(ciphertext)" in a
 * regular MODE_PRIVATE SharedPreferences. The AES key never leaves the
 * Keystore. Preference keys are stored in the clear (they are not secret — only
 * the values are), and no per-use authentication is required so values remain
 * readable across cold starts.
 */
class EncryptedPrefs(context: Context, name: String) {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Decrypted value for [key], or null when absent or undecryptable. */
    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            null
        }
    }

    /** Encrypts and stores [value], or removes [key] when [value] is null. */
    fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    /** Wipes all stored values. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? {
        val parts = stored.split(":")
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = existingKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun existingKey(): SecretKey? =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bvb_prefs_key"
        const val TAG_BITS = 128
        const val TRANSFORMATION =
            "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/" +
                KeyProperties.ENCRYPTION_PADDING_NONE
    }
}
