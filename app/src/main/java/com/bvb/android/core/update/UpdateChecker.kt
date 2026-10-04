package com.bvb.android.core.update

import com.bvb.android.BuildConfig
import com.bvb.android.core.AppLog
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** An update that is newer than the installed build. */
data class UpdateInfo(
    val versionName: String,
    val url: String,
)

/**
 * Checks GitHub Releases for a newer APK than the one installed.
 *
 * The app is distributed via GitHub (Obtainium), so the latest *non-prerelease*
 * release on [RELEASES_URL] is the source of truth. This talks to GitHub
 * directly with a dedicated, header-free client so no backend/auth headers ever
 * leak to GitHub. All failures are swallowed: a missing network or a GitHub
 * rate-limit must never crash or block the app.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val json: Json,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "bvb-android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val release = json.decodeFromString(GithubRelease.serializer(), body)
                if (release.draft || release.prerelease) return@use null
                val tag = release.tagName?.trimStart('v', 'V') ?: return@use null
                if (isNewer(latest = tag, current = BuildConfig.VERSION_NAME)) {
                    UpdateInfo(versionName = tag, url = release.htmlUrl ?: RELEASES_PAGE)
                } else {
                    null
                }
            }
        }.onFailure { AppLog.e(TAG, "update check failed", it) }.getOrNull()
    }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
    )

    companion object {
        private const val TAG = "UpdateChecker"
        private const val RELEASES_URL =
            "https://api.github.com/repos/SatsRouting/bvb-android/releases/latest"
        const val RELEASES_PAGE =
            "https://github.com/SatsRouting/bvb-android/releases/latest"

        /** Compares dotted numeric versions, e.g. "0.1.2" > "0.1.0". */
        fun isNewer(latest: String, current: String): Boolean {
            val l = latest.split('.').map { it.toIntOrNull() ?: 0 }
            val c = current.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(l.size, c.size)) {
                val a = l.getOrElse(i) { 0 }
                val b = c.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
