package com.bvb.android.core.network

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The Go backend encodes empty slices as the JSON literal `null`, which the
 * kotlinx.serialization converter cannot map to a List (Retrofit erases Kotlin
 * nullability, so a nullable return type does not help). Rewrite such bodies
 * to an empty array before deserialization.
 */
@Singleton
class NullJsonInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val contentType = body.contentType()
        // Only inspect small JSON responses; streams (e.g. SSE) are untouched.
        if (contentType?.subtype != "json") return response
        val length = body.contentLength()
        if (length > 8) return response

        val peeked = response.peekBody(9).string().trim()
        if (peeked != "null") return response

        body.close()
        return response.newBuilder()
            .body("[]".toResponseBody(contentType))
            .build()
    }
}
