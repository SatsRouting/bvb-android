package com.bvb.android.feature.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.Notification
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notifications: List<Notification> = emptyList(),
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
) : ViewModel() {
    val uiState = MutableStateFlow(NotificationsUiState())

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "notification", "notification_read", "sse_reconnected" -> load(silent = true)
                }
            }
        }
    }

    fun refresh() {
        uiState.value = uiState.value.copy(refreshing = true)
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                val items = api.getNotifications().orEmpty()
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = null, notifications = items,
                )
            } catch (e: Exception) {
                if (!silent) {
                    uiState.value = uiState.value.copy(
                        loading = false, refreshing = false, error = ApiError.messageOf(e),
                    )
                }
            }
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            try {
                api.markNotificationRead(id)
                uiState.value = uiState.value.copy(
                    notifications = uiState.value.notifications.map {
                        if (it.id == id) it.copy(read = true) else it
                    }
                )
            } catch (e: Exception) {
                // non-fatal
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        state.error?.let { ErrorBanner(it, Modifier.padding(16.dp)) }

        if (state.loading) {
            FullScreenLoading()
        } else {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { viewModel.refresh() }) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.notifications.isEmpty()) {
                        item {
                            Text(
                                "No notifications.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.notifications, key = { it.id }) { n ->
                        NotificationCard(n, onClick = { if (!n.read) viewModel.markRead(n.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(n: Notification, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (n.read) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    n.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold,
                )
                Text(
                    formatTime(n.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(n.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatTime(iso: String): String = try {
    DateTimeFormatter.ofPattern("dd MMM HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(iso))
} catch (e: Exception) {
    ""
}
