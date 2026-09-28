package com.bvb.android.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.AdminMessage
import com.bvb.android.data.model.AdminReplyRequest
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.StatusChip
import com.bvb.android.ui.components.noAutofill
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SupportChatUiState(
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
    val subject: String = "Support",
    val closed: Boolean = false,
    val messages: List<AdminMessage> = emptyList(),
)

@HiltViewModel
class SupportChatViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])
    val uiState = MutableStateFlow(SupportChatUiState())

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "admin_conversation_message", "admin_conversation_closed" -> {
                        val evConvId = event.data?.jsonObject?.get("conversation_id")?.jsonPrimitive?.content
                        if (evConvId == null || evConvId == conversationId) load(silent = true)
                    }
                    "sse_reconnected" -> load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                // Conversation metadata (subject/status) comes from the list endpoint.
                val conversation = try {
                    api.listAdminConversations().orEmpty().firstOrNull { it.id == conversationId }
                } catch (e: Exception) {
                    null
                }
                val messages = api.getAdminConversationMessages(conversationId).orEmpty()
                uiState.value = uiState.value.copy(
                    loading = false,
                    error = null,
                    subject = conversation?.subject?.ifEmpty { "Support" } ?: uiState.value.subject,
                    closed = conversation?.status == "closed",
                    messages = messages,
                )
            } catch (e: Exception) {
                if (!silent) {
                    uiState.value = uiState.value.copy(loading = false, error = ApiError.messageOf(e))
                }
            }
        }
    }

    fun send(text: String) {
        uiState.value = uiState.value.copy(sending = true)
        viewModelScope.launch {
            try {
                api.replyAdminConversation(conversationId, AdminReplyRequest(text))
                load(silent = true)
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(error = ApiError.messageOf(e))
            } finally {
                uiState.value = uiState.value.copy(sending = false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportChatScreen(
    onBack: () -> Unit,
    viewModel: SupportChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.subject, style = MaterialTheme.typography.titleMedium)
                        if (state.closed) {
                            Spacer(Modifier.widthIn(min = 8.dp))
                            StatusChip("closed")
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            state.error?.let { ErrorBanner(it, Modifier.padding(8.dp)) }

            if (state.loading) {
                Box(Modifier.weight(1f)) { FullScreenLoading() }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        SupportMessageBubble(msg)
                    }
                }
            }

            if (state.closed) {
                Text(
                    "This conversation has been closed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { if (it.length <= 2000) input = it },
                        placeholder = { Text("Message…") },
                        modifier = Modifier.weight(1f).noAutofill(),
                        maxLines = 4,
                    )
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                viewModel.send(text)
                                input = ""
                            }
                        },
                        enabled = !state.sending,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportMessageBubble(msg: AdminMessage) {
    val fromSelf = msg.isFromSelf
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromSelf) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromSelf) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (fromSelf) 12.dp else 2.dp,
                bottomEnd = if (fromSelf) 2.dp else 12.dp,
            ),
        ) {
            Column(Modifier.padding(10.dp).widthIn(max = 300.dp)) {
                if (!fromSelf) {
                    Text(
                        if (msg.isAdmin) "Support" else msg.senderAvatar,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                SelectionContainer {
                    Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    formatTime(msg.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
