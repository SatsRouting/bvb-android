package com.bvb.android.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bvb.android.MainActivity
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Renders notifications as Android local notifications, de-duplicated by the
 * notification `id` so the same alert never shows twice regardless of the
 * channel it arrived through:
 *  - **SSE** (`notification` events) while the app is in foreground, and
 *  - **background push** (a content-less "ping" from ntfy triggers
 *    [fetchAndNotifyUnread], which pulls the real content via the API).
 */
@Singleton
class LocalNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    sse: SseClient,
    private val api: ApiService,
    private val session: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var nextId = 1000

    /**
     * Bounded set of notification ids already surfaced, newest-last. Guards
     * against SSE+push double delivery and against re-showing the same unread
     * notification on every ping.
     */
    private val shownIds = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (size > MAX_TRACKED) iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            return added
        }
    }

    init {
        createChannel()
        scope.launch {
            sse.events.collect { event ->
                if (event.type == "notification") {
                    val obj = event.data?.jsonObject ?: return@collect
                    val id = obj["id"]?.jsonPrimitive?.content
                    val title = obj["title"]?.jsonPrimitive?.content ?: "BVB"
                    val message = obj["message"]?.jsonPrimitive?.content ?: return@collect
                    showNotification(id, title, message)
                }
            }
        }
    }

    /**
     * Background-push entry point: a ping carries no content, so fetch the
     * unread notifications and surface the ones not shown yet.
     */
    suspend fun fetchAndNotifyUnread() {
        if (session.token == null) return
        val list = runCatching { api.getNotifications() }.getOrNull().orEmpty()
        list.filter { !it.read }.forEach { n ->
            showNotification(n.id, n.title.ifBlank { "BVB" }, n.message)
        }
    }

    /**
     * Marks the current unread notifications as already-shown WITHOUT notifying,
     * so enabling background push does not replay a backlog — only notifications
     * created afterwards will pop.
     */
    suspend fun primeShownFromServer() {
        if (session.token == null) return
        val list = runCatching { api.getNotifications() }.getOrNull().orEmpty()
        synchronized(shownIds) { list.forEach { shownIds.add(it.id) } }
    }

    /** Shows a notification unless [id] was already surfaced. */
    fun showNotification(id: String?, title: String, message: String) {
        if (message.isBlank()) return
        if (id != null) {
            synchronized(shownIds) {
                if (shownIds.contains(id)) return
                shownIds.add(id)
            }
        }
        notify(title, message)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trade updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notify(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(nextId++, notification)
    }

    private companion object {
        const val CHANNEL_ID = "bvb_updates"
        const val MAX_TRACKED = 200
    }
}
