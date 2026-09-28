package com.bvb.android.feature.marketplace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bvb.android.R
import com.bvb.android.ui.theme.OrderBuy
import com.bvb.android.ui.theme.OrderSell
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.OrderResponse
import com.bvb.android.data.model.UserStats
import com.bvb.android.ui.components.AvatarImage
import com.bvb.android.ui.components.CircularTimer
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.formatFiat
import com.bvb.android.ui.components.formatSats
import com.bvb.android.ui.components.PaymentMethodIcons
import com.bvb.android.ui.components.ReputationStars
import com.bvb.android.ui.components.totalTimelockSeconds
import androidx.compose.ui.unit.sp
import com.bvb.android.ui.theme.SuccessGreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MarketplaceUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val orders: List<OrderResponse> = emptyList(),
    val typeFilter: String? = null,
    val currencyFilter: String? = null,
    val paymentMethodsFilter: List<String> = emptyList(),
    val btcPrice: Double? = null,
    val priceCurrency: String = "EUR",
    /** Creator reputation, keyed by creator id; absent = still loading. */
    val creatorStats: Map<String, UserStats> = emptyMap(),
)

@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
    val session: SessionManager,
) : ViewModel() {
    val uiState = MutableStateFlow(MarketplaceUiState())

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "new_market_order", "order_removed", "order_cancelled",
                    "order_expired", "orders_batch_updated", "sse_reconnected" -> refresh(silent = true)
                }
            }
        }
    }

    fun setTypeFilter(type: String?) {
        uiState.value = uiState.value.copy(typeFilter = type)
        load()
    }

    fun setCurrencyFilter(currency: String?) {
        uiState.value = uiState.value.copy(currencyFilter = currency)
        load()
    }

    fun togglePaymentMethod(method: String) {
        val current = uiState.value.paymentMethodsFilter
        uiState.value = uiState.value.copy(
            paymentMethodsFilter = if (method in current) current - method else current + method,
        )
        load()
    }

    fun clearPaymentMethods() {
        uiState.value = uiState.value.copy(paymentMethodsFilter = emptyList())
        load()
    }

    fun refresh(silent: Boolean = false) {
        if (!silent) uiState.value = uiState.value.copy(refreshing = true)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val s = uiState.value
                val page = api.listOrders(
                    // Like the web app: "I want to buy" shows sell orders
                    // (you buy from sellers) and vice versa.
                    type = when (s.typeFilter) {
                        "buy" -> "sell"
                        "sell" -> "buy"
                        else -> null
                    },
                    currency = s.currencyFilter,
                    paymentMethod = s.paymentMethodsFilter.takeIf { it.isNotEmpty() }?.joinToString(","),
                )
                val price = try {
                    api.getBitcoinPrice(uiState.value.priceCurrency).price
                } catch (e: Exception) {
                    uiState.value.btcPrice
                }
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = null,
                    orders = page.items, btcPrice = price,
                )
                loadCreatorStats(page.items.map { it.creatorId }.distinct())
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = ApiError.messageOf(e),
                )
            }
        }
    }

    /** Fetches (and caches) the reputation of each order creator, like the web app. */
    private fun loadCreatorStats(creatorIds: List<String>) {
        creatorIds
            .filter { it.isNotEmpty() && it !in uiState.value.creatorStats }
            .forEach { creatorId ->
                viewModelScope.launch {
                    val stats = try {
                        api.getUserStats(creatorId)
                    } catch (e: Exception) {
                        UserStats()
                    }
                    uiState.update { it.copy(creatorStats = it.creatorStats + (creatorId to stats)) }
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    onOrderClick: (orderId: String, creatorAvatarId: String?) -> Unit,
    viewModel: MarketplaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh(silent = true) }

    Column(Modifier.fillMaxSize()) {
            MarketplaceFilters(
                typeFilter = state.typeFilter,
                onTypeChange = viewModel::setTypeFilter,
                currencyFilter = state.currencyFilter,
                onCurrencyChange = viewModel::setCurrencyFilter,
                paymentMethods = state.paymentMethodsFilter,
                onTogglePaymentMethod = viewModel::togglePaymentMethod,
                onClearPaymentMethods = viewModel::clearPaymentMethods,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            state.error?.let { ErrorBanner(it, Modifier.padding(16.dp)) }

            if (state.loading) {
                FullScreenLoading()
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() },
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.orders.isEmpty()) {
                            item {
                                Text(
                                    "No open orders right now.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(state.orders, key = { it.id }) { order ->
                            OrderCard(
                                order = order,
                                stats = state.creatorStats[order.creatorId],
                                onClick = { onOrderClick(order.id, order.creatorAvatarId.ifEmpty { null }) },
                            )
                        }
                    }
                }
            }
    }
}

private val filterCurrencies = listOf(
    null to "Any", "EUR" to "EUR", "USD" to "USD", "CHF" to "CHF",
    "GBP" to "GBP", "CAD" to "CAD", "AUD" to "AUD",
)

private val filterPaymentMethods = listOf(
    "revolut" to "Revolut", "wise" to "Wise", "sepa" to "SEPA", "instantsepa" to "InstantSepa",
    "amazon" to "Amazon", "paysend" to "Paysend", "yuh" to "Yuh", "zen" to "Zen",
    "usdt" to "USDT", "usdtliquid" to "USDT Liquid",
)

/** Mirrors the web app's "I want to [Any] and use [Any] pay with [Any]" filter bar. */
@Composable
private fun MarketplaceFilters(
    typeFilter: String?,
    onTypeChange: (String?) -> Unit,
    currencyFilter: String?,
    onCurrencyChange: (String?) -> Unit,
    paymentMethods: List<String>,
    onTogglePaymentMethod: (String) -> Unit,
    onClearPaymentMethods: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fixed-width thirds so growing labels ellipsize instead of pushing the
    // other filters off screen.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FilterCell(label = "I want to", modifier = Modifier.weight(1f)) {
            FilterDropdown(
                label = when (typeFilter) {
                    "buy" -> "Buy"
                    "sell" -> "Sell"
                    else -> "Any"
                },
                leading = { TypeDot(typeFilter) },
            ) { close ->
                listOf(null to "Any", "buy" to "Buy", "sell" to "Sell").forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { TypeDot(value) },
                        trailingIcon = if (typeFilter == value) {
                            { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = { onTypeChange(value); close() },
                    )
                }
            }
        }

        FilterCell(label = "and use", modifier = Modifier.weight(1f)) {
            FilterDropdown(
                label = filterCurrencies.first { it.first == currencyFilter }.second,
            ) { close ->
                filterCurrencies.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = if (currencyFilter == value) {
                            { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = { onCurrencyChange(value); close() },
                    )
                }
            }
        }

        FilterCell(label = "pay with", modifier = Modifier.weight(1f)) {
            FilterDropdown(
                label = when {
                    paymentMethods.isEmpty() -> "Any"
                    paymentMethods.size == 1 ->
                        filterPaymentMethods.first { it.first == paymentMethods[0] }.second
                    else -> "${paymentMethods.size} selected"
                },
            ) { close ->
                if (paymentMethods.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Clear all", color = MaterialTheme.colorScheme.primary) },
                        onClick = { onClearPaymentMethods(); close() },
                    )
                }
                filterPaymentMethods.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = if (value in paymentMethods) {
                            { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = { onTogglePaymentMethod(value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterCell(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun TypeDot(type: String?) {
    // Swapped on purpose, like the web filter dots: "I want to buy" shows
    // sell orders, so the dot carries the sell color (and vice versa).
    val color = when (type) {
        "buy" -> OrderSell
        "sell" -> OrderBuy
        else -> return
    }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun FilterDropdown(
    label: String,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            leading?.let {
                it()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

@Composable
private fun OrderCard(order: OrderResponse, stats: UserStats?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OrderAvatarWithBadges(order)
                if (stats != null) {
                    Spacer(Modifier.height(4.dp))
                    ReputationStars(level = stats.trustLevel, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(formatSats(order.amount), style = MaterialTheme.typography.titleMedium)
                Text(
                    // Indicative fiat total computed from the order's own price
                    // (like the web app): the backend leaves fiat_amount at 0
                    // in the marketplace list.
                    formatFiat(order.amount / 100_000_000.0 * order.price, order.currencyCode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (order.marginPercentage >= 0) "+" else ""}${order.marginPercentage}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    PaymentMethodIcons(order.paymentMethod, sizeDp = 22, maxVisible = 6)
                }
            }
            Spacer(Modifier.width(12.dp))
            if (order.timeRemaining > 0) {
                CircularTimer(
                    remainingSeconds = order.timeRemaining,
                    totalSeconds = totalTimelockSeconds(order.status),
                )
            } else {
                Text(
                    "Expired",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Avatar with the two web-app badges: buy/sell dot at the bottom-right and
 * presence dot at the top-left.
 */
@Composable
private fun OrderAvatarWithBadges(order: OrderResponse) {
    val sideColor = if (order.type == "buy") OrderBuy else OrderSell
    val presenceColor = if (order.creatorOnline) SuccessGreen else androidx.compose.ui.graphics.Color.Gray
    val ringColor = MaterialTheme.colorScheme.surface
    Box(Modifier.size(48.dp)) {
        AvatarImage(
            order.creatorAvatarId,
            sizeDp = 44,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            Modifier
                .size(14.dp)
                .align(Alignment.BottomEnd)
                .border(1.5.dp, ringColor, CircleShape)
                .padding(1.5.dp)
                .background(sideColor, CircleShape),
        )
        Box(
            Modifier
                .size(12.dp)
                .align(Alignment.TopStart)
                .border(1.5.dp, ringColor, CircleShape)
                .padding(1.5.dp)
                .background(presenceColor, CircleShape),
        )
        // Settlement-chain logo badge (bottom-left), mirroring the web app's
        // order-chain-badge: same bitcoin.png / liquid.png logos on a surface disc.
        val isBitcoin = order.settlementChain == "bitcoin"
        Box(
            Modifier
                .size(20.dp)
                .align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(1.5.dp, ringColor, CircleShape)
                .padding(1.5.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(if (isBitcoin) R.drawable.bitcoin else R.drawable.liquid),
                contentDescription = if (isBitcoin) "Bitcoin" else "Liquid",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}
