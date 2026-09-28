package com.bvb.android.feature.trade

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.TradeResponse
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.StatusChip
import com.bvb.android.ui.components.formatFiat
import com.bvb.android.ui.components.formatSats
import com.bvb.android.ui.theme.OrderBuy
import com.bvb.android.ui.theme.OrderSell
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class TradesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val trades: List<TradeResponse> = emptyList(),
    val activeOnly: Boolean = true,
)

@HiltViewModel
class TradesViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
    val session: SessionManager,
) : ViewModel() {
    val uiState = MutableStateFlow(TradesUiState())

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "trade_update", "trade_expired", "payment_verified", "sse_reconnected" -> load(silent = true)
                }
            }
        }
    }

    fun setActiveOnly(active: Boolean) {
        uiState.value = uiState.value.copy(activeOnly = active)
        load()
    }

    fun refresh() {
        uiState.value = uiState.value.copy(refreshing = true)
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                val page = api.listTrades(status = if (uiState.value.activeOnly) "active" else null)
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = null, trades = page.items,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradesScreen(
    onTradeClick: (String) -> Unit,
    viewModel: TradesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.load(silent = true) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.activeOnly,
                onClick = { viewModel.setActiveOnly(true) },
                label = { Text("Active") },
            )
            FilterChip(
                selected = !state.activeOnly,
                onClick = { viewModel.setActiveOnly(false) },
                label = { Text("All") },
            )
        }

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
                    if (state.trades.isEmpty()) {
                        item {
                            Text(
                                "No trades yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.trades, key = { it.id }) { trade ->
                        TradeCard(
                            trade = trade,
                            myUserId = viewModel.session.userId.orEmpty(),
                            onClick = { onTradeClick(trade.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeCard(trade: TradeResponse, myUserId: String, onClick: () -> Unit) {
    val iAmBuyer = trade.buyerId == myUserId
    val counterparty = if (iAmBuyer) trade.sellerAvatar else trade.buyerAvatar
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (iAmBuyer) "Buying" else "Selling",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (iAmBuyer) OrderBuy else OrderSell,
                )
                StatusChip(trade.status)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatSats(trade.amount), style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatFiat(trade.finalFiatAmount, trade.currencyCode),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "with $counterparty",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
