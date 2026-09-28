package com.bvb.android.feature.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.data.model.AdminReplyRequest
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.LoadingButton
import com.bvb.android.ui.components.noAutofill
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SupportTicketUiState(
    val sending: Boolean = false,
    val error: String? = null,
    val openedTicketId: String? = null,
)

@HiltViewModel
class SupportTicketViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {
    val uiState = MutableStateFlow(SupportTicketUiState())

    fun openTicket(message: String) {
        uiState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val conversation = api.openSupportTicket(AdminReplyRequest(message))
                uiState.update { it.copy(sending = false, openedTicketId = conversation.id) }
            } catch (e: Exception) {
                uiState.update { it.copy(sending = false, error = ApiError.messageOf(e)) }
            }
        }
    }

    fun consumeOpenedTicket() {
        uiState.update { it.copy(openedTicketId = null, error = null) }
    }
}

/**
 * Bottom sheet to open a support ticket, reachable from the sidebar
 * (like the web app's "OPEN TICKET" entry).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportTicketSheet(
    onOpened: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: SupportTicketViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var text by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Navigate to the newly created ticket once the server confirms it.
    LaunchedEffect(state.openedTicketId) {
        state.openedTicketId?.let { id ->
            viewModel.consumeOpenedTicket()
            onOpened(id)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SupportAgent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Contact support", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Describe your issue and the support team will get back to you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(it)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 2000) text = it },
                placeholder = { Text("Describe your issue…") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().noAutofill(),
            )
            Spacer(Modifier.height(16.dp))
            LoadingButton(
                onClick = { viewModel.openTicket(text.trim()) },
                loading = state.sending,
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send") }
        }
    }
}
