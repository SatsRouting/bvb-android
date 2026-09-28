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

        if (response.code == 401 && session.token != null &&
            chain.request().url.encodedPath !in AUTH_401_EXEMPT_PATHS
        ) {
            session.onLogout()
        }
        return response
    }

    private companion object {
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
