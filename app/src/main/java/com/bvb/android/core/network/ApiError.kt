package com.bvb.android.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * The backend mixes JSON errors ({"error": ..., "message": ...}) and plain
 * text bodies from http.Error; normalize both into a readable message.
 */
object ApiError {
    private val json = Json { ignoreUnknownKeys = true }

    fun messageOf(t: Throwable): String = when (t) {
        is HttpException -> {
            val body = try {
                t.response()?.errorBody()?.string()?.trim().orEmpty()
            } catch (e: Exception) {
                ""
            }
            parseBody(body) ?: defaultForCode(t.code())
        }
        is java.io.IOException -> "Network error, check your connection"
        // JVM Errors (NoClassDefFoundError, OutOfMemoryError, ...) would
        // otherwise show up as an empty "Unexpected error": keep the class
        // name so the real cause is visible in the UI.
        is Error -> "${t.javaClass.simpleName}: ${t.message ?: "internal error"}"
        else -> t.message ?: t.javaClass.simpleName
    }

    fun isUnauthorized(t: Throwable): Boolean = t is HttpException && t.code() == 401

    private fun parseBody(body: String): String? {
        if (body.isEmpty()) return null
        if (body.startsWith("{")) {
            return try {
                val obj = json.parseToJsonElement(body).jsonObject
                // The backend localizes this one in Italian; the app is
                // English-only, so translate by error code.
                if (obj["error"]?.jsonPrimitive?.content == "session_expired") {
                    return "Session expired or revoked, please log in again"
                }
                obj["message"]?.jsonPrimitive?.content
                    ?: obj["error"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                null
            }
        }
        // Plain-text error from Go's http.Error
        return body.take(300)
    }

    private fun defaultForCode(code: Int): String = when (code) {
        401 -> "Session expired, please log in again"
        403 -> "Not allowed"
        404 -> "Not found"
        429 -> "Too many requests, please wait a moment"
        else -> "Server error ($code)"
    }
}
