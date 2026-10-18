package com.bvb.android.core.sse

import com.bvb.android.BuildConfig
import com.bvb.android.core.session.SessionManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

data class SseEvent(val type: String, val data: JsonElement?)

enum class SseStatus { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * SSE connection with exponential-backoff reconnect, mirroring the web
 * client's SSEContext. Events are re-published on a SharedFlow keyed by the
 * SSE event name; a synthetic "sse_reconnected" event lets screens resync
 * state they may have missed while offline.
 */
@Singleton
class SseClient @Inject constructor(
    private val session: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // The server emits a keep-alive every 30s; a read gap longer than that
        // means the connection died silently (e.g. after Doze / a network
        // switch). A 70s read timeout lets OkHttp surface it as onFailure so the
        // backoff reconnect kicks in, instead of hanging forever on a half-open
        // socket (which left the app "stuck loading" on resume).
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<SseEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SseEvent> = _events

    private val _status = MutableStateFlow(SseStatus.DISCONNECTED)
    val status: StateFlow<SseStatus> = _status

    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null
    private var attempts = 0
    @Volatile
    private var shouldRun = false

    // True once the first connection of this process has opened. Any later open
    // (backoff reconnect OR a fresh connect() on returning to foreground) is a
    // "reconnect" and triggers a resync of screens via the synthetic event.
    @Volatile
    private var hasConnectedOnce = false

    fun connect() {
        shouldRun = true
        attempts = 0
        openConnection()
    }

    fun disconnect() {
        shouldRun = false
        reconnectJob?.cancel()
        eventSource?.cancel()
        eventSource = null
        _status.value = SseStatus.DISCONNECTED
    }

    private fun openConnection() {
        val token = session.token ?: return
        eventSource?.cancel()
        _status.value = SseStatus.CONNECTING

        val request = Request.Builder()
            .url("${BuildConfig.BASE_URL}/api/sse")
            .header("Authorization", "Bearer $token")
            .header("X-Client", "mobile")
            .header("Accept", "text/event-stream")
            .build()

        eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                val wasReconnect = hasConnectedOnce
                hasConnectedOnce = true
                attempts = 0
                _status.value = SseStatus.CONNECTED
                if (wasReconnect) {
                    _events.tryEmit(SseEvent("sse_reconnected", null))
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val payload = try {
                    if (data.isBlank()) null else json.parseToJsonElement(data)
                } catch (e: Exception) {
                    null
                }
                _events.tryEmit(SseEvent(type ?: "message", payload))
            }

            override fun onClosed(eventSource: EventSource) {
                scheduleReconnect()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (response?.code == 401 || response?.code == 403) {
                    // Revoked/expired session: don't loop, the 401 handling of
                    // the next REST call will log the user out.
                    _status.value = SseStatus.DISCONNECTED
                    return
                }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        _status.value = SseStatus.CONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffMs = min(30_000L, 1_000L * (1L shl min(attempts, 5)))
            attempts++
            delay(backoffMs)
            if (shouldRun) openConnection()
        }
    }
}
