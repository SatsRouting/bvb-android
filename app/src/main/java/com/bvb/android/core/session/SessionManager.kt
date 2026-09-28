package com.bvb.android.core.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bvb.android.core.security.BiometricUnlock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the session state. The JWT is persisted in EncryptedSharedPreferences
 * (key material in the Android Keystore). The session password and the
 * decrypted PGP private key live only in memory and are wiped on logout,
 * mirroring the web client's model.
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext context: Context,
    private val biometric: BiometricUnlock,
) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "bvb_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _isLoggedIn = MutableStateFlow(token != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    /** In-memory only: needed for escrow actions and PGP key unlock. */
    @Volatile
    var sessionPassword: String? = null

    /** In-memory only: armored PGP private key fetched after login. */
    @Volatile
    var pgpPrivateKeyArmored: String? = null

    /** In-memory only: our own armored PGP public key. */
    @Volatile
    var pgpPublicKeyArmored: String? = null

    /**
     * Passphrase protecting the PGP private key. The backend locks the key
     * with "avatarId:password" (same scheme the web client uses), not with
     * the bare account password.
     */
    val pgpPassphrase: String?
        get() {
            val avatar = avatarId ?: return null
            val password = sessionPassword ?: return null
            return "$avatar:$password"
        }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        private set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var avatarId: String?
        get() = prefs.getString(KEY_AVATAR_ID, null)
        private set(value) = prefs.edit().putString(KEY_AVATAR_ID, value).apply()

    fun onLogin(token: String, userId: String, avatarId: String, password: String) {
        this.token = token
        this.userId = userId
        this.avatarId = avatarId
        this.sessionPassword = password
        // Keep the App lock enrollment for the same user across logout, but wipe
        // it if a different account logs in on this device.
        biometric.clearIfOtherAccount(avatarId)
        _isLoggedIn.value = true
    }

    fun onLogout() {
        prefs.edit().clear().apply()
        sessionPassword = null
        pgpPrivateKeyArmored = null
        pgpPublicKeyArmored = null
        // Note: the biometric App lock enrollment is intentionally NOT wiped here,
        // so the same user keeps it across logout. It is cleared on next login if
        // a different account signs in (see onLogin -> clearIfOtherAccount).
        _isLoggedIn.value = false
    }

    private companion object {
        const val KEY_TOKEN = "jwt"
        const val KEY_USER_ID = "user_id"
        const val KEY_AVATAR_ID = "avatar_id"
    }
}
