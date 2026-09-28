package com.bvb.android.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the account password encrypted under an Android Keystore AES/GCM key
 * that requires a fresh strong-biometric authentication for every use
 * (auth-per-operation, bound via a [BiometricPrompt.CryptoObject]).
 *
 * This lets the app auto-restore the session after a cold start (the JWT
 * survives on disk, but the password and PGP keys are memory-only) without the
 * user re-typing the password, while the password remains decryptable ONLY
 * behind the device biometric. The key is invalidated automatically if a new
 * biometric is enrolled on the device (setInvalidatedByBiometricEnrollment),
 * so a stolen device with an added fingerprint cannot recover the password.
 */
@Singleton
class BiometricUnlock @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the user has enrolled biometric unlock (ciphertext present). */
    val isEnabled: Boolean
        get() = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    /**
     * Whether the device can authenticate the user: a strong biometric on any
     * version, plus the device credential (PIN/pattern/password) on Android 11+.
     */
    fun canAuthenticate(): Boolean =
        BiometricManager.from(context).canAuthenticate(allowedAuthenticators()) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** Cipher initialized for ENCRYPT; wrap in a CryptoObject to enroll. */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }

    /**
     * Cipher initialized for DECRYPT using the stored IV, or null if there is
     * nothing enrolled or the key was invalidated (e.g. a new biometric was
     * enrolled), in which case the caller should fall back to the password.
     */
    fun decryptCipher(): Cipher? {
        val iv = prefs.getString(KEY_IV, null)
            ?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val key = existingKey() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Avatar id the current enrollment belongs to (null when not enrolled). */
    val enrolledAvatarId: String?
        get() = prefs.getString(KEY_AVATAR, null)

    /**
     * After a successful ENCRYPT auth, persist the ciphertext of the password,
     * tagged with the [avatarId] it belongs to so a different account logging in
     * on the same device doesn't inherit it.
     */
    fun persist(authenticatedCipher: Cipher, password: String, avatarId: String) {
        val ct = authenticatedCipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ct, Base64.DEFAULT))
            .putString(KEY_IV, Base64.encodeToString(authenticatedCipher.iv, Base64.DEFAULT))
            .putString(KEY_AVATAR, avatarId)
            .apply()
    }

    /**
     * Wipes the enrollment if it belongs to a different account than [avatarId].
     * Called on login so the same user keeps their App lock across logout, while
     * a different user on the same device starts clean.
     */
    fun clearIfOtherAccount(avatarId: String) {
        if (isEnabled && enrolledAvatarId != avatarId) {
            disable()
        }
    }

    /** After a successful DECRYPT auth, recover the stored password. */
    fun recover(authenticatedCipher: Cipher): String? {
        val ct = prefs.getString(KEY_CIPHERTEXT, null)
            ?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        return try {
            String(authenticatedCipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Disable and wipe all biometric material (stored ciphertext + Keystore key). */
    fun disable() {
        prefs.edit().clear().apply()
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
        }
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
        )
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: allow strong biometrics OR the device credential
            // (PIN/pattern/password). Timeout 0 = auth required for every use,
            // bound to a CryptoObject.
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            // Pre-API 30: biometric-only, per-operation; invalidated if a new
            // biometric is enrolled (device-credential binding needs API 30+).
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun existingKey(): SecretKey? =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        /**
         * Authenticators offered: strong biometrics always, plus the device
         * credential (PIN/pattern/password) on Android 11+ (crypto-bound
         * device-credential auth is only reliable from API 30).
         */
        fun allowedAuthenticators(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            }

        private const val PREFS = "bvb_biometric"
        private const val KEY_CIPHERTEXT = "pw_ct"
        private const val KEY_IV = "pw_iv"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_ALIAS = "bvb_biometric_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TAG_BITS = 128
        private const val TRANSFORMATION =
            "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/" +
                KeyProperties.ENCRYPTION_PADDING_NONE
    }
}

/**
 * Shows a system [BiometricPrompt] bound to [cipher]. On success the callback
 * receives the authenticated cipher (ready for doFinal); on cancel/error the
 * error callback fires with a human-readable message.
 */
fun promptBiometric(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String,
    subtitle: String,
    negativeText: String,
    onSuccess: (Cipher) -> Unit,
    onError: (String) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authed = result.cryptoObject?.cipher
                if (authed != null) onSuccess(authed) else onError("Biometric error")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        },
    )
    val authenticators = BiometricUnlock.allowedAuthenticators()
    val infoBuilder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(authenticators)
    // A custom negative button is only allowed when the device credential is NOT
    // an option; otherwise the system supplies its own "Use PIN" affordance.
    if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
        infoBuilder.setNegativeButtonText(negativeText)
    }
    prompt.authenticate(infoBuilder.build(), BiometricPrompt.CryptoObject(cipher))
}

/**
 * Convenience wrapper for the "fill the password with biometrics" flow used by
 * the in-app password prompts: opens the [BiometricPrompt], decrypts the stored
 * password and hands it back via [onPassword]. Falls back to [onError] (so the
 * caller can keep the manual password field) when nothing is enrolled, the key
 * was invalidated, or the user cancels.
 */
fun promptBiometricRecover(
    activity: FragmentActivity,
    biometric: BiometricUnlock,
    title: String,
    subtitle: String,
    onPassword: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val cipher = biometric.decryptCipher()
    if (cipher == null) {
        onError("Biometric unlock is unavailable. Enter your password.")
        return
    }
    promptBiometric(
        activity = activity,
        cipher = cipher,
        title = title,
        subtitle = subtitle,
        negativeText = "Use password",
        onSuccess = { authed ->
            val password = biometric.recover(authed)
            if (password != null) onPassword(password)
            else onError("Could not recover your password. Enter it manually.")
        },
        onError = onError,
    )
}
