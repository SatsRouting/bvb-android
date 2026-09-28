package com.bvb.android.feature.order

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.MatchOrderResponse
import com.bvb.android.data.model.OrderEntity
import com.bvb.android.data.model.PasswordRequest
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.LoadingButton
import com.bvb.android.ui.components.LoadingOutlinedButton
import com.bvb.android.ui.components.PaymentSummaryCard
import com.bvb.android.ui.components.QrCode
import com.bvb.android.ui.components.SmallCenteredNote
import com.bvb.android.ui.components.StatusChip
import com.bvb.android.ui.components.StepIconHeader
import com.bvb.android.ui.components.WaitingSpinner
import com.bvb.android.ui.components.formatCountdown
import com.bvb.android.ui.components.formatDate
import com.bvb.android.ui.components.formatFiat
import com.bvb.android.ui.components.formatSats
import com.bvb.android.ui.components.formatTimeMmSs
import com.bvb.android.ui.components.explorerTxUrl
import com.bvb.android.ui.components.openUrl
import com.bvb.android.ui.components.paymentMethodLabel
import com.bvb.android.ui.theme.OrderBuy
import com.bvb.android.ui.theme.OrderSell
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/** Why the pending invoice is not shown, mirrored from the web app. */
enum class InvoiceErrorKind { EXPIRED, PROCESSING, UNAVAILABLE }

/** Steps of the take-order flow, mirroring the web app's TakeOrderModal. */
enum class TakeStep { INVOICE, ESCROW, FUNDED }

data class OrderDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val order: OrderEntity? = null,
    val creatorAvatarId: String? = null,
    val matching: Boolean = false,
    val matchResult: MatchOrderResponse? = null,
    val takeStep: TakeStep? = null,
    val goToTradeId: String? = null,
    val cancelled: Boolean = false,
    val cancelling: Boolean = false,
    val invoice: com.bvb.android.data.model.OrderInvoiceResponse? = null,
    val invoiceLoading: Boolean = false,
    val invoiceError: InvoiceErrorKind? = null,
    val paid: Boolean = false,
)

@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val api: ApiService,
    val session: SessionManager,
    private val sse: SseClient,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val orderId: String = checkNotNull(savedStateHandle["orderId"])
    // Seeded from the marketplace so the creator avatar shows instantly, avoiding
    // the brief flash of the raw creator UUID while GET /users/{id}/stats resolves.
    private val seededAvatar: String? = savedStateHandle["avatar"]
    val uiState = MutableStateFlow(OrderDetailUiState(creatorAvatarId = seededAvatar))

    init {
        load()
        viewModelScope.launch {
            sse.events.collect { event ->
                val data = event.data?.jsonObject
                when (event.type) {
                    "payment_verified" -> {
                        // Taker flow: our deposit for the matched trade was paid.
                        val evTradeId = data?.get("trade_id")?.jsonPrimitive?.content
                        val match = uiState.value.matchResult
                        if (match != null && evTradeId == match.tradeId &&
                            uiState.value.takeStep == TakeStep.INVOICE
                        ) {
                            uiState.update { it.copy(takeStep = TakeStep.ESCROW) }
                        }
                        // Creator flow: the pending order's deposit was paid.
                        val evOrderId = data?.get("order_id")?.jsonPrimitive?.content
                        if (evOrderId == orderId) {
                            uiState.update { it.copy(paid = true) }
                            load(silent = true)
                        }
                    }
                    "trade_update" -> {
                        // Taker flow: escrow funded, the trade is ready.
                        val evTradeId = data?.get("trade_id")?.jsonPrimitive?.content
                        val status = data?.get("status")?.jsonPrimitive?.content
                        val evName = data?.get("event")?.jsonPrimitive?.content
                        val match = uiState.value.matchResult
                        if (match != null && evTradeId == match.tradeId &&
                            (status == "funded" || evName == "trade_funded")
                        ) {
                            onTradeFunded()
                        }
                    }
                    "order_status_changed", "order_escrow_funding",
                    "order_cancelled", "order_expired", "order_corrupted" -> {
                        val evOrderId = data?.get("order_id")?.jsonPrimitive?.content
                            ?: data?.get("id")?.jsonPrimitive?.content
                        if (evOrderId == orderId) load(silent = true)
                    }
                    "sse_reconnected" -> load(silent = true)
                }
            }
        }
        // Like the web app: poll while the order waits for the deposit payment.
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                if (uiState.value.order?.status == "pending") load(silent = true)
            }
        }
        // Fallback polling of the matched trade, in case an SSE event is
        // missed (mirrors TakeOrderModal's 10s interval).
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                val state = uiState.value
                val match = state.matchResult ?: continue
                if (state.takeStep == TakeStep.FUNDED) continue
                try {
                    val trade = api.getTrade(match.tradeId)
                    when {
                        trade.status == "funded" -> onTradeFunded()
                        trade.status != "matched" && state.takeStep == TakeStep.INVOICE ->
                            uiState.update { it.copy(takeStep = TakeStep.ESCROW) }
                    }
                } catch (e: Exception) {
                    // Transient network error; the next tick retries.
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                val order = api.getOrder(orderId)
                uiState.update { it.copy(loading = false, order = order, error = null) }
                if (order.creatorId == session.userId) {
                    if (order.status == "pending") {
                        loadInvoice()
                    } else if (uiState.value.invoice != null) {
                        uiState.update { it.copy(invoice = null, invoiceError = null) }
                    }
                } else if (uiState.value.creatorAvatarId == null) {
                    // GET /orders/{id} has no creator_avatar_id (only the
                    // marketplace list does), so resolve it via user stats.
                    try {
                        val stats = api.getUserStats(order.creatorId)
                        uiState.update { it.copy(creatorAvatarId = stats.avatarId.ifEmpty { null }) }
                    } catch (e: Exception) {
                        // Fall back to the raw creator id in the UI.
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    uiState.update { it.copy(loading = false, error = ApiError.messageOf(e)) }
                }
            }
        }
    }

    private suspend fun loadInvoice() {
        // Avoid flashing the loading state on the 10s poll refresh.
        if (uiState.value.invoice == null) uiState.update { it.copy(invoiceLoading = true) }
        try {
            val invoice = api.getOrderInvoice(orderId)
            uiState.update { it.copy(invoice = invoice, invoiceError = null, invoiceLoading = false) }
        } catch (e: Exception) {
            // Same mapping as the web app: 410 = expired, 400 = deposit paid /
            // escrow funding in progress, anything else = unavailable.
            val kind = when ((e as? HttpException)?.code()) {
                410 -> InvoiceErrorKind.EXPIRED
                400 -> InvoiceErrorKind.PROCESSING
                else -> InvoiceErrorKind.UNAVAILABLE
            }
            uiState.update { it.copy(invoice = null, invoiceError = kind, invoiceLoading = false) }
        }
    }

    fun match() {
        uiState.update { it.copy(matching = true, error = null) }
        viewModelScope.launch {
            try {
                val result = api.matchOrder(orderId)
                uiState.update {
                    it.copy(matching = false, matchResult = result, takeStep = TakeStep.INVOICE)
                }
            } catch (e: Exception) {
                uiState.update { it.copy(matching = false, error = ApiError.messageOf(e)) }
            }
        }
    }

    private fun onTradeFunded() {
        val match = uiState.value.matchResult ?: return
        if (uiState.value.takeStep == TakeStep.FUNDED) return
        uiState.update { it.copy(takeStep = TakeStep.FUNDED) }
        // Same as the web app: show "Trade Ready" briefly, then move on.
        viewModelScope.launch {
            delay(2500)
            uiState.update { it.copy(goToTradeId = match.tradeId) }
        }
    }

    /** The matcher deposit invoice expired without being paid. */
    fun onTakeInvoiceExpired() {
        uiState.update {
            it.copy(
                matchResult = null,
                takeStep = null,
                error = "Time expired for invoice payment",
            )
        }
        load(silent = true)
    }

    fun cancel() {
        // The password is optional here: the server only uses it to rebuild
        // the refund if the presigned transaction is invalid (the web app
        // doesn't send it at all).
        val password = session.sessionPassword.orEmpty()
        uiState.update { it.copy(cancelling = true) }
        viewModelScope.launch {
            try {
                api.cancelOrder(orderId, PasswordRequest(password))
                uiState.update { it.copy(cancelled = true, cancelling = false) }
            } catch (e: Exception) {
                uiState.update { it.copy(error = ApiError.messageOf(e), cancelling = false) }
            }
        }
    }
}

/** Indicative fiat value like the web app: sats -> BTC x price. */
private fun formatIndicativePrice(amountSats: Long, price: Double, currency: String): String =
    formatFiat(amountSats / 100_000_000.0 * price, currency)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    onTradeStarted: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: OrderDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.cancelled) {
        onBack()
        return
    }
    state.goToTradeId?.let {
        onTradeStarted(it)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        if (state.loading) {
            FullScreenLoading()
            return@Scaffold
        }
        val order = state.order ?: run {
            Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorBanner(state.error ?: "Order not found")
            }
            return@Scaffold
        }
        val isCreator = order.creatorId == viewModel.session.userId

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            state.error?.let {
                ErrorBanner(it)
                Spacer(Modifier.height(12.dp))
            }

            // Take-order flow in a bottom sheet. Closing it simply jumps to
            // the trade detail: the matched order left the marketplace, so
            // the trade page is where the flow continues anyway.
            val match = state.matchResult
            val step = state.takeStep
            if (match != null && step != null) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { onTradeStarted(match.tradeId) },
                    sheetState = sheetState,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 32.dp),
                    ) {
                        TakeOrderFlow(
                            order = order,
                            match = match,
                            step = step,
                            onExpired = { viewModel.onTakeInvoiceExpired() },
                        )
                    }
                }
            }

            if (isCreator) {
                CreatorDetailsTable(order)
                ActionsHeader()
                when (order.status) {
                    // Pending orders can't be cancelled: they expire on their
                    // own if the deposit isn't paid. Show the invoice instead,
                    // in a bottom sheet like the take-order flow. Dismissable,
                    // with a button to bring it back.
                    "pending" -> {
                        // Opens only on demand via the button below.
                        var showInvoiceSheet by remember { androidx.compose.runtime.mutableStateOf(false) }
                        Text(
                            "This order is waiting for your deposit payment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showInvoiceSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Show deposit invoice") }

                        if (showInvoiceSheet) {
                            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                            ModalBottomSheet(
                                onDismissRequest = { showInvoiceSheet = false },
                                sheetState = sheetState,
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 24.dp)
                                        .padding(bottom = 32.dp),
                                ) {
                                    PendingInvoiceSection(
                                        order = order,
                                        invoice = state.invoice,
                                        invoiceLoading = state.invoiceLoading,
                                        invoiceError = state.invoiceError,
                                        paid = state.paid,
                                    )
                                }
                            }
                        }
                    }
                    "open" -> OpenOrderSection(
                        order = order,
                        cancelling = state.cancelling,
                        onCancel = { viewModel.cancel() },
                    )
                    else -> NoActionsText("No actions are available for this order.")
                }
            } else {
                TakerDetailsTable(order, state.creatorAvatarId)
                ActionsHeader()
                var remainingSecs by remember { mutableLongStateOf(1L) }
                // The button hides the moment the order expires, like the web.
                LaunchedEffect(order.expiresAt) {
                    while (true) {
                        remainingSecs = try {
                            Instant.parse(order.expiresAt).epochSecond - Instant.now().epochSecond
                        } catch (e: Exception) {
                            0L
                        }
                        delay(1000)
                    }
                }
                if (order.status == "open" && remainingSecs > 0) {
                    LoadingButton(
                        onClick = { viewModel.match() },
                        loading = state.matching,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (order.type == "sell") "Buy Bitcoin" else "Sell Bitcoin") }
                } else {
                    NoActionsText("This order is no longer available.")
                }
            }
        }
    }
}

@Composable
private fun ActionsHeader() {
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text("Actions", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun NoActionsText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Detail rows a non-creator sees, mirroring the web app's OrderDetailModal:
 * what you send/receive, price+premium, methods, creator and expiry countdown.
 */
@Composable
private fun TakerDetailsTable(order: OrderEntity, creatorAvatarId: String?) {
    val isSell = order.type == "sell"
    val methodsLabel = order.paymentMethod.joinToString(", ") { paymentMethodLabel(it) }
    val satsLabel = formatSats(order.amount)
    val fiatLabel = "≈ ${formatIndicativePrice(order.amount, order.price, order.currencyCode)}"
    val fiatViaLabel = if (methodsLabel.isNotEmpty()) "$fiatLabel via $methodsLabel" else fiatLabel

    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(order.expiresAt) {
        while (true) {
            remaining = try {
                Instant.parse(order.expiresAt).epochSecond - Instant.now().epochSecond
            } catch (e: Exception) {
                0L
            }
            delay(1000)
        }
    }

    Text("Order Details", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    DetailRow("Order ID") {
        Text(
            order.id.substringBefore('-'),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
    DetailRow("You send") { DetailText(if (isSell) fiatViaLabel else satsLabel) }
    DetailRow("You receive") { DetailText(if (isSell) satsLabel else fiatViaLabel) }
    DetailRow("Price and Premium") {
        DetailText(
            "${formatFiat(order.price, order.currencyCode)} - Premium: ${order.marginPercentage}%"
        )
    }
    DetailRow("Payment methods") { DetailText(methodsLabel) }
    // Never show the raw creator UUID: until the avatar id is known, show a
    // neutral placeholder (covers non-marketplace entry points too).
    DetailRow("Creator") { DetailText(creatorAvatarId ?: "…") }
    if (remaining > 0) {
        DetailRow("Expires in") {
            Text(
                formatCountdown(remaining),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Field rows the creator sees, matching the web app's OrderDetail table. */
@Composable
private fun CreatorDetailsTable(order: OrderEntity) {
    val context = LocalContext.current
    val isBuy = order.type == "buy"

    Text("Order Details", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    DetailRow("Order ID") {
        Text(
            order.id,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    DetailRow("Type") {
        Text(
            if (isBuy) "BUY" else "SELL",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isBuy) OrderBuy else OrderSell,
        )
    }
    DetailRow("Status") { StatusChip(order.status) }
    DetailRow("Bitcoin Amount") { DetailText(formatSats(order.amount)) }
    DetailRow("Fiat Value") {
        DetailText(formatIndicativePrice(order.amount, order.price, order.currencyCode))
    }
    DetailRow("BTC Price") { DetailText(formatFiat(order.price, order.currencyCode)) }
    DetailRow("Premium") { DetailText("${order.marginPercentage}%") }
    DetailRow("Payment Methods") {
        DetailText(order.paymentMethod.joinToString(", ") { paymentMethodLabel(it) })
    }
    DetailRow("Created on") { DetailText(formatDate(order.createdAt)) }

    // Funding / refund transactions, tappable like the web app's explorer links.
    order.fundingTxid?.takeIf { it.isNotEmpty() && it != "pending" }?.let { txid ->
        TxLinkRow("Funding TX", txid) {
            openUrl(
                context,
                explorerTxUrl(
                    order.settlementChain,
                    txid,
                    amount = order.fundingAmount,
                    valueBlinder = order.valueBlinder,
                    assetBlinder = order.assetBlinder,
                ),
            )
        }
    }
    order.refundTxid?.takeIf { it.isNotEmpty() }?.let { txid ->
        TxLinkRow("Refund TX", txid) {
            openUrl(context, explorerTxUrl(order.settlementChain, txid, blindingData = order.refundBlindingData))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        value()
    }
}

@Composable
private fun DetailText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun TxLinkRow(label: String, txid: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            txid,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

/**
 * Take-order flow, mirroring the web app's TakeOrderModal steps:
 * invoice -> payment confirmed / escrow creation -> trade ready.
 */
@Composable
private fun TakeOrderFlow(
    order: OrderEntity,
    match: MatchOrderResponse,
    step: TakeStep,
    onExpired: () -> Unit,
) {
    when (step) {
        TakeStep.INVOICE -> TakeInvoiceStep(order, match, onExpired)
        TakeStep.ESCROW -> {
            StepIconHeader(Icons.Default.Check, "Payment Confirmed")
            Spacer(Modifier.height(6.dp))
            Text(
                "Creating escrow and securing funds in multisig…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            WaitingSpinner("Waiting for on-chain confirmation")
            Spacer(Modifier.height(4.dp))
            SmallCenteredNote("Do not close this page until the process is complete")
        }
        TakeStep.FUNDED -> {
            StepIconHeader(Icons.Default.CheckCircle, "Trade Ready")
            Spacer(Modifier.height(6.dp))
            Text(
                "Escrow funded successfully. The trade is now active.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TakeInvoiceStep(
    order: OrderEntity,
    match: MatchOrderResponse,
    onExpired: () -> Unit,
) {
    val context = LocalContext.current
    // Taking a SELL order makes you the buyer (10% deposit); taking a BUY
    // order makes you the seller (100% deposit). Same wording as the web app.
    val isTakingSell = order.type == "sell"

    var remaining by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(match.expiresAt) {
        while (true) {
            val left = try {
                Instant.parse(match.expiresAt).epochSecond - Instant.now().epochSecond
            } catch (e: Exception) {
                Long.MAX_VALUE
            }
            remaining = left
            if (left <= 0) {
                onExpired()
                break
            }
            delay(1000)
        }
    }

    Text(
        if (isTakingSell) "Make a deposit to buy" else "Deposit the Bitcoin to sell",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (isTakingSell) {
            "To buy ${formatSats(order.amount)} you must deposit ${formatSats(match.depositAmount)} (10%) as a guarantee."
        } else {
            "Lock the security Bitcoin deposit to sell ${formatSats(order.amount)}: you must lock the entire amount as a security deposit."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))

    PaymentSummaryCard(
        depositLabel = "Deposit (${if (isTakingSell) "10%" else "100%"})",
        depositSat = match.depositAmount,
        serviceFeeSat = match.serviceFee,
        totalSat = match.invoice.invoiceAmountSat,
    )

    if (remaining in 1 until Long.MAX_VALUE) {
        Spacer(Modifier.height(10.dp))
        Text(
            "This invoice will expire in ${formatTimeMmSs(remaining)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        QrCode(content = match.invoice.bolt11.uppercase())
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("invoice", match.invoice.bolt11))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Copy invoice") }
    Spacer(Modifier.height(12.dp))
    WaitingSpinner("Waiting for payment confirmation…")
    Spacer(Modifier.height(4.dp))
    SmallCenteredNote("Do not close this page until payment is confirmed")
}

/** "Order is live" box + cancel button, like the web app's open-order actions. */
@Composable
private fun OpenOrderSection(
    order: OrderEntity,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(order.expiresAt) {
        while (true) {
            remaining = try {
                Instant.parse(order.expiresAt).epochSecond - Instant.now().epochSecond
            } catch (e: Exception) {
                0L
            }
            delay(1000)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Order is live", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Your order is published on the marketplace and waiting to be matched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (remaining > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Expires in ${formatCountdown(remaining)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    LoadingOutlinedButton(
        onClick = onCancel,
        loading = cancelling,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Cancel Order") }
}

/** Deposit invoice for the creator of a pending order, like the web app. */
@Composable
private fun PendingInvoiceSection(
    order: OrderEntity,
    invoice: com.bvb.android.data.model.OrderInvoiceResponse?,
    invoiceLoading: Boolean,
    invoiceError: InvoiceErrorKind?,
    paid: Boolean,
) {
    val context = LocalContext.current
    val isBuy = order.type == "buy"

    // Deposit confirmed (SSE) or the backend says the invoice is already
    // paid (HTTP 400): show the escrow-funding progress state.
    if (paid || invoiceError == InvoiceErrorKind.PROCESSING) {
        WaitingSpinner("Escrow funding in progress")
        Spacer(Modifier.height(4.dp))
        Text(
            "Your deposit is being moved to the escrow multisig. This may take a few minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (invoiceLoading && invoice == null) {
        WaitingSpinner("Loading invoice…")
        return
    }

    if (invoice == null) {
        Text(
            when (invoiceError) {
                InvoiceErrorKind.EXPIRED -> "This invoice has expired. The order will be removed automatically."
                else -> "No payable invoice is available for this order."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(invoice.expiresAt) {
        while (true) {
            remaining = try {
                Instant.parse(invoice.expiresAt).epochSecond - Instant.now().epochSecond
            } catch (e: Exception) {
                0L
            }
            delay(1000)
        }
    }

    Text(
        if (isBuy) "Make a deposit to buy" else "Deposit the Bitcoin to sell",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (isBuy) {
            "To buy ${formatSats(order.amount)} you must deposit ${formatSats(invoice.depositAmount)} (10%) as a guarantee."
        } else {
            "Lock the security Bitcoin deposit to sell ${formatSats(order.amount)}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))

    // Payment summary: same rows as the web app, network fee derived from the
    // invoice total minus deposit and service fee.
    PaymentSummaryCard(
        depositLabel = "Deposit (${if (isBuy) "10%" else "100%"})",
        depositSat = invoice.depositAmount,
        serviceFeeSat = invoice.serviceFee,
        totalSat = invoice.invoice.invoiceAmountSat,
    )

    Spacer(Modifier.height(10.dp))
    Text(
        if (remaining > 0) "This invoice will expire in ${formatCountdown(remaining)}" else "Invoice expired",
        style = MaterialTheme.typography.bodySmall,
        color = if (remaining > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        QrCode(content = invoice.invoice.bolt11.uppercase())
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("invoice", invoice.invoice.bolt11))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Copy invoice") }
    Spacer(Modifier.height(12.dp))
    WaitingSpinner("Waiting for payment confirmation…")
}

