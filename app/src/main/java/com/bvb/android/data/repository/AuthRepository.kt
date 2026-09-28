package com.bvb.android.data.repository

import com.bvb.android.core.network.ApiService
import com.bvb.android.core.pgp.PgpService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.data.model.CreateAvatarRequest
import com.bvb.android.data.model.CreateAvatarResponse
import com.bvb.android.data.model.LoginRequest
import com.bvb.android.data.model.PasswordRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
    @Suppress("unused") private val pgp: PgpService,
) {
    /**
     * Logs in and stores the session. Also fetches the PGP private key with
     * the same password so chat decryption works immediately (kept in memory
     * only, like the web client).
     */
    suspend fun login(avatarId: String, password: String, turnstileToken: String?): LoginResult {
        val resp = api.login(LoginRequest(avatarId, password, turnstileToken), turnstileToken)
        val token = resp.token
            ?: throw IllegalStateException("Backend did not return a token; update the server (X-Client mobile support)")
        session.onLogin(token, resp.userId, resp.avatarId, password)

        if (resp.hasPgpKeys) {
            try {
                val keys = api.revealPgpKeys(PasswordRequest(password))
                session.pgpPrivateKeyArmored = keys.pgpPrivateKey
                session.pgpPublicKeyArmored = keys.pgpPublicKey
            } catch (e: Exception) {
                // Chat will prompt again; login itself succeeded.
            }
        }
        return LoginResult(migratedMnemonic = resp.mnemonic.takeIf { resp.walletMigrated })
    }

    suspend fun createAvatar(password: String, referralCode: String?, turnstileToken: String?): CreateAvatarResponse =
        api.createAvatar(CreateAvatarRequest(password, referralCode?.ifBlank { null }, turnstileToken), turnstileToken)

    /**
     * Re-fetches and unlocks the PGP keys with the account password. Needed
     * when the app process restarted: the JWT survives on disk but keys and
     * password are memory-only.
     */
    suspend fun unlockPgpKeys(password: String) {
        val keys = api.revealPgpKeys(PasswordRequest(password))
        session.pgpPrivateKeyArmored = keys.pgpPrivateKey
        session.pgpPublicKeyArmored = keys.pgpPublicKey
        session.sessionPassword = password
    }

    suspend fun logout() {
        try {
            api.logout()
        } finally {
            session.onLogout()
        }
    }
}

data class LoginResult(val migratedMnemonic: String?)
