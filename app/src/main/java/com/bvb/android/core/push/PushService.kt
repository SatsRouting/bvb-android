package com.bvb.android.core.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bvb.android.MainActivity
import com.bvb.android.core.notifications.LocalNotifier
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Persistent foreground service that keeps a long-lived connection to our
 * self-hosted ntfy relay (the embedded UnifiedPush distributor). Each delivered
 * message is a content-less "ping": it only wakes the app, which then fetches
 * the real notifications via the authenticated API ([LocalNotifier.fetchAndNotifyUnread]).
 *
 * Reconnects with exponential backoff and is START_STICKY so Android restarts it
 * after a kill; [BootReceiver] restarts it after a reboot.
 */
@AndroidEntryPoint
class PushService : Service() {

    @Inject lateinit var localNotifier: LocalNotifier
    @Inject lateinit var pushManager: PushManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var listenJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream
        .pingInterval(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val url = pushManager.subscribeUrl()
        if (url == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startListening(url)
        return START_STICKY
    }

    private fun startListening(url: String) {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) error("ntfy HTTP ${resp.code}")
                        attempt = 0
                        val source = resp.body?.source() ?: return@use
                        while (isActive && !source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isNotBlank()) handleLine(line)
                        }
                    }
                } catch (e: Exception) {
                    ensureActive()
                }
                if (!isActive) break
                val backoff = min(60_000L, 1_000L * (1L shl min(attempt, 6)))
                attempt++
                delay(backoff)
            }
        }
    }

    private fun handleLine(line: String) {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        // ntfy /json emits {"event":"open|keepalive|message", ...}; only "message"
        // carries a ping. The payload content is intentionally ignored (ping-only).
        if (obj["event"]?.jsonPrimitive?.content == "message") {
            scope.launch { localNotifier.fetchAndNotifyUnread() }
        }
    }

    private fun startForegroundCompat() {
        createChannel()
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("BVB")
            .setContentText("Background notifications active")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pending)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background connection",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        listenJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "bvb_push_service"
        const val NOTIF_ID = 42
    }
}
