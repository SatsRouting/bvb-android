package com.bvb.android.feature.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.AdminConversation
import com.bvb.android.data.model.TradeResponse
import com.bvb.android.ui.components.AvatarImage
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.StatusChip
import com.bvb.android.ui.theme.SuccessGreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 30

data class MessagesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val conversations: List<TradeResponse> = emptyList(),
    val conversationsTotal: Int = 0,
    val adminConversations: List<AdminConversation> = emptyList(),
)

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val api: ApiService,
    val session: SessionManager,
    private val sse: SseClient,
) : ViewModel() {
    val uiState = MutableStateFlow(MessagesUiState())

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "new_message", "message_sent", "trade_update",
                    "admin_conversation_message", "admin_conversation_new",
                    "admin_conversation_closed", "presence_changed",
                    "sse_reconnected" -> load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) uiState.value = uiState.value.copy(refreshing = true)
        viewModelScope.launch {
            try {
                coroutineScope {
                    val tradesDeferred = async { api.listConversations(limit = maxOf(PAGE_SIZE, uiState.value.conversations.size)) }
                    val adminDeferred = async {
                        try {
                            api.listAdminConversations().orEmpty()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    val page = tradesDeferred.await()
                    uiState.value = uiState.value.copy(
                        loading = false,
                        refreshing = false,
                        error = null,
                        conversations = page.items,
                        conversationsTotal = page.totalCount,
                        adminConversations = adminDeferred.await(),
                    )
                }
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = ApiError.messageOf(e),
                )
            }
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            try {
                val page = api.listConversations(
                    limit = PAGE_SIZE,
                    offset = uiState.value.conversations.size,
                )
                uiState.value = uiState.value.copy(
                    conversations = uiState.value.conversations + page.items,
                    conversationsTotal = page.totalCount,
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(error = ApiError.messageOf(e))
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onOpenChat: (String) -> Unit,
    onOpenSupport: (String) -> Unit,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        state.error?.let { ErrorBanner(it, Modifier.padding(16.dp)) }

        if (state.loading) {
            FullScreenLoading()
            return
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.load() },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("Support", style = MaterialTheme.typography.titleMedium)
                }
                if (state.adminConversations.isEmpty()) {
                    item {
                        Text(
                            "No support tickets.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.adminConversations, key = { "admin-${it.id}" }) { conv ->
                    AdminConversationCard(conv, onClick = { onOpenSupport(conv.id) })
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Trades", style = MaterialTheme.typography.titleMedium)
                }
                if (state.conversations.isEmpty()) {
                    item {
                        Text(
                            "You don't have any trade conversations yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.conversations, key = { "trade-${it.id}" }) { trade ->
                    TradeConversationCard(
                        trade = trade,
                        myUserId = viewModel.session.userId.orEmpty(),
                        onClick = { onOpenChat(trade.id) },
                    )
                }
                if (state.conversations.size < state.conversationsTotal) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { viewModel.loadMore() }) { Text("Load more") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminConversationCard(conv: AdminConversation, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.SupportAgent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(conv.subject.ifEmpty { "Support" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Support",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(conv.status)
        }
    }
}

@Composable
private fun TradeConversationCard(trade: TradeResponse, myUserId: String, onClick: () -> Unit) {
    val iAmBuyer = trade.buyerId == myUserId
    val counterpartyAvatar = if (iAmBuyer) trade.sellerAvatar else trade.buyerAvatar
    val counterpartyOnline = if (iAmBuyer) trade.sellerOnline else trade.buyerOnline
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Counterparty avatar with a presence dot, like the web list.
            Box {
                AvatarImage(counterpartyAvatar, sizeDp = 36)
                Surface(
                    shape = CircleShape,
                    color = if (counterpartyOnline) SuccessGreen else Color.Gray,
                    modifier = Modifier.size(10.dp).align(Alignment.BottomEnd),
                ) {}
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Trade: ${trade.id.take(8)}", style = MaterialTheme.typography.titleSmall)
                Text(
                    counterpartyAvatar,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(trade.status)
        }
    }
}
