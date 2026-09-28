package com.bvb.android.feature.order

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.OrderResponse
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.StatusChip
import com.bvb.android.ui.components.formatCountdown
import com.bvb.android.ui.components.formatFiat
import com.bvb.android.ui.components.formatSats
import com.bvb.android.ui.theme.OrderBuy
import com.bvb.android.ui.theme.OrderSell
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class MyOrdersUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val orders: List<OrderResponse> = emptyList(),
    val activeOnly: Boolean = true,
)

@HiltViewModel
class MyOrdersViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
) : ViewModel() {
    val uiState = MutableStateFlow(MyOrdersUiState())

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "order_created", "order_status_changed", "order_cancelled",
                    "order_expired", "order_corrupted", "order_escrow_funding",
                    "payment_verified", "sse_reconnected" -> load(silent = true)
                }
            }
        }
    }

    fun setActiveOnly(active: Boolean) {
        uiState.value = uiState.value.copy(activeOnly = active)
        load()
    }

    fun load(silent: Boolean = false) {
        if (!silent) uiState.value = uiState.value.copy(refreshing = true)
        viewModelScope.launch {
            try {
                val page = api.listUserOrders(
                    status = if (uiState.value.activeOnly) "active" else null,
                )
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = null, orders = page.items,
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
fun MyOrdersScreen(
    onOrderClick: (String) -> Unit,
    viewModel: MyOrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.activeOnly,
                onClick = { viewModel.setActiveOnly(true) },
                label = { Text("Active orders") },
            )
            FilterChip(
                selected = !state.activeOnly,
                onClick = { viewModel.setActiveOnly(false) },
                label = { Text("All orders") },
            )
        }

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
                if (state.orders.isEmpty()) {
                    item {
                        Column {
                            Text("No orders found", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Orders you create appear here. Create one from the marketplace.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(state.orders, key = { it.id }) { order ->
                    MyOrderCard(order = order, onClick = { onOrderClick(order.id) })
                }
            }
        }
    }
}

@Composable
private fun MyOrderCard(order: OrderResponse, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (order.type == "buy") "BUY" else "SELL",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (order.type == "buy") OrderBuy else OrderSell,
                )
                StatusChip(order.status)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatSats(order.amount), style = MaterialTheme.typography.titleMedium)
                // Indicative fiat value: sats * price, same as the web app.
                Text(
                    formatFiat(order.amount / 1e8 * order.price, order.currencyCode.ifEmpty { "USD" }),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatDate(order.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (order.status in listOf("pending", "open") && order.timeRemaining > 0) {
                    // Tick locally every second; the server value only re-seeds
                    // the countdown when the list reloads.
                    var remaining by remember(order.id, order.timeRemaining) {
                        mutableLongStateOf(order.timeRemaining)
                    }
                    LaunchedEffect(order.id, order.timeRemaining) {
                        while (remaining > 0) {
                            delay(1000)
                            remaining--
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "expires in ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            formatCountdown(remaining),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDate(iso: String): String = try {
    DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(iso))
} catch (e: Exception) {
    iso.take(10)
}
