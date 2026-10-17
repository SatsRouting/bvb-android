package com.bvb.android.core.push

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bvb.android.BuildConfig
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.notifications.LocalNotifier
import com.bvb.android.core.security.EncryptedPrefs
import com.bvb.android.core.session.SessionManager
import com.bvb.android.data.model.PushRegisterRequest
import com.bvb.android.data.model.PushUnregisterRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Coordinates the self-hosted background push feature (UnifiedPush over our own
 * ntfy, embedded distributor). Responsibilities:
 *  - persist the user's on/off preference and the per-device random topic,
 *  - register/unregister the device endpoint with the backend,
 *  - start/stop the [PushService] foreground connection.
 *
 * Everything is a no-op when [isSupported] is false (no `NTFY_BASE_URL` baked
 * into the build), so the feature is opt-in and cannot regress the SSE path.
 */
@Singleton
class PushManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val session: SessionManager,
    private val localNotifier: LocalNotifier,
) {
    private val prefs = EncryptedPrefs(context, "bvb_push")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True when the build knows a ntfy server; otherwise the feature is hidden. */
    val isSupported: Boolean get() = ntfyBase().isNotEmpty()

    /** User preference. Persisted; survives logout so it re-arms on next login. */
    var isEnabled: Boolean
        get() = prefs.getString(KEY_ENABLED) == "1"
        private set(v) = prefs.putString(KEY_ENABLED, if (v) "1" else null)

    private var topic: String?
        get() = prefs.getString(KEY_TOPIC)
        set(v) = prefs.putString(KEY_TOPIC, v)

    private fun ntfyBase(): String = BuildConfig.NTFY_BASE_URL.trim().trimEnd('/')

    /** Stable per-device topic (created once, reused across logins). */
    private fun ensureTopic(): String {
        topic?.let { return it }
        // UnifiedPush convention: topics start with "up"; keep it unguessable.
        val t = "up" + UUID.randomUUID().toString().replace("-", "")
        topic = t
        return t
    }

    /** Endpoint the backend POSTs the ping to (publish URL). */
    private fun endpoint(): String = "${ntfyBase()}/${ensureTopic()}"

    /** Stream URL the foreground service subscribes to (newline-delimited JSON). */
    fun subscribeUrl(): String? {
        if (!isSupported) return null
        val t = topic ?: return null
        return "${ntfyBase()}/$t/json"
    }

    /** User toggled the Settings switch. [onResult] runs on the IO scope. */
    fun setEnabled(enabled: Boolean, onResult: (Boolean) -> Unit = {}) {
        if (!isSupported) { onResult(false); return }
        scope.launch {
            val ok = if (enabled) registerAndStart() else unregisterAndStop(removeServer = true)
            if (enabled && ok) isEnabled = true
            if (!enabled) isEnabled = false
            onResult(ok)
        }
    }

    /** Called when the user is (re)authenticated: re-arm if previously enabled. */
    fun onLogin() {
        if (!isSupported || !isEnabled) return
        scope.launch { registerAndStart() }
    }

    /** Called on logout while the token is still valid: drop the server endpoint
     *  and stop the service, but keep the preference so it re-arms next login. */
    suspend fun onLogout() {
        if (!isSupported) return
        unregisterAndStop(removeServer = true)
    }

    /** Called from BootReceiver: resume the connection after a reboot. */
    fun onBoot() {
        if (!isSupported || !isEnabled || session.token == null) return
        startService()
    }

    private suspend fun registerAndStart(): Boolean {
        if (session.token == null) return false
        return try {
            api.registerPush(
                PushRegisterRequest(endpoint = endpoint(), deviceLabel = android.os.Build.MODEL ?: "android"),
            )
            // Avoid replaying the backlog of existing unread notifications.
            localNotifier.primeShownFromServer()
            startService()
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun unregisterAndStop(removeServer: Boolean): Boolean {
        stopService()
        if (removeServer && session.token != null) {
            val ep = topic?.let { endpoint() }
            if (ep != null) runCatching { api.unregisterPush(PushUnregisterRequest(ep)) }
        }
        return true
    }

    private fun startService() {
        if (subscribeUrl() == null) return
        val intent = Intent(context, PushService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopService() {
        context.stopService(Intent(context, PushService::class.java))
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TOPIC = "topic"
    }
}
