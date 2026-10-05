package com.bvb.android.core.network

import com.bvb.android.core.session.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Bearer auth and identifies this client as the native app; the backend
 * returns the JWT in the login body only when X-Client: mobile is present.
 *
 * Like the web client's axios interceptor, a 401 on any authenticated call
 * means the session expired or was revoked: wipe it so the UI falls back to
 * the login screen instead of leaving the app stuck with a dead token.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val session: SessionManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("X-Client", "mobile")
        session.token?.let { builder.header("Authorization", "Bearer $it") }
        val response = chain.proceed(builder.build())

        if (response.code == 401 && session.token != null) {
            // A 401 on an exempt path is normally just "wrong password typed",
            // but a *revoked* token (e.g. the user logged out on the web, which
            // bumps token_version) also lands here with a distinct body. Without
            // this, unlocking from the lock screen would loop forever on
            // "wrong password" with a dead token. Detect the revoked-session
            // marker so the app drops cleanly to the login screen instead.
            val path = chain.request().url.encodedPath
            if (path !in AUTH_401_EXEMPT_PATHS || isRevokedSession(response)) {
                session.onLogout()
            }
        }
        return response
    }

    /**
     * The backend returns `{"error":"session_expired"}` when a token was
     * revoked (logout/ban bumped token_version), as opposed to a plain
     * "Invalid password" body for a mistyped password. Peek the body without
     * consuming it so downstream callers still read the original stream.
     */
    private fun isRevokedSession(response: Response): Boolean = try {
        response.peekBody(PEEK_BYTES).string().contains("session_expired")
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val PEEK_BYTES = 512L

        /**
         * Endpoints where a 401 does NOT mean an expired session:
         * login/registration (wrong credentials) and the secret-reveal
         * endpoints, whose 401 just means "wrong password typed".
         */
        val AUTH_401_EXEMPT_PATHS = setOf(
            "/api/login",
            "/api/avatar/create",
            "/api/user/mnemonic",
            "/api/user/pgp-private-key",
        )
    }
}
