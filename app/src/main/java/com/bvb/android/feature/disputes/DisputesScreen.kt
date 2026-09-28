package com.bvb.android.feature.disputes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.Dispute
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.StatusChip
import com.bvb.android.ui.components.formatDate
import com.bvb.android.ui.components.formatSats
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class DisputesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val disputes: List<Dispute> = emptyList(),
)

@HiltViewModel
class DisputesViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
) : ViewModel() {
    val uiState = MutableStateFlow(DisputesUiState())

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "dispute_created", "dispute_resolved_in_favor", "dispute_resolved_against",
                    "new_evidence_added", "evidence_submitted", "sse_reconnected" -> load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) uiState.value = uiState.value.copy(refreshing = true)
        viewModelScope.launch {
            try {
                val page = api.listDisputes()
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = null, disputes = page.items,
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = ApiError.messageOf(e),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisputesScreen(
    onDisputeClick: (String) -> Unit,
    viewModel: DisputesViewModel = hiltViewModel(),
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
                if (state.disputes.isEmpty()) {
                    item {
                        Column {
                            Text("No disputes found", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "You don't have any active or resolved disputes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(state.disputes, key = { it.id }) { dispute ->
                    DisputeCard(dispute, onClick = { onDisputeClick(dispute.id) })
                }
            }
        }
    }
}

@Composable
private fun DisputeCard(dispute: Dispute, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dispute ${dispute.id.take(8)}…", style = MaterialTheme.typography.titleSmall)
                StatusChip(dispute.status)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Trade ${dispute.tradeId.take(8)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatSats(dispute.tradeAmount), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                dispute.reason.take(80),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatDate(dispute.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
