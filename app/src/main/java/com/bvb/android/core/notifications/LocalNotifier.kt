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
 * Bridges SSE `notification` events to Android local notifications while the
 * app process is alive (no push infrastructure server-side).
 */
@Singleton
class LocalNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    sse: SseClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var nextId = 1000

    init {
        createChannel()
        scope.launch {
            sse.events.collect { event ->
                if (event.type == "notification") {
                    val obj = event.data?.jsonObject ?: return@collect
                    val title = obj["title"]?.jsonPrimitive?.content ?: "BVB"
                    val message = obj["message"]?.jsonPrimitive?.content ?: return@collect
                    notify(title, message)
                }
            }
        }
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
    }
}
