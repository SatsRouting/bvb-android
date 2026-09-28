package com.bvb.android.feature.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.R
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.VoucherPurchase
import com.bvb.android.data.model.VoucherStock
import com.bvb.android.ui.components.AppSheet
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.InvoiceSheet
import com.bvb.android.ui.components.LoadingButton
import com.bvb.android.ui.components.formatCountdown
import com.bvb.android.ui.theme.SuccessGreen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ServicesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val stock: VoucherStock? = null,
    // Purchase state
    val purchasing: Boolean = false,
    val purchase: VoucherPurchase? = null,
    val purchaseError: String? = null,
    val secondsLeft: Long = 0,
    // The invoice sheet can be closed while waiting: SSE/polling keeps
    // running and the voucher dialog still opens when payment completes.
    val sheetDismissed: Boolean = false,
    // Late-payment refunds needing the user's action (LND/LNbits backends):
    // a payment arrived after the purchase expired and must be refunded via a
    // user-supplied BOLT11 invoice.
    val refunds: List<VoucherPurchase> = emptyList(),
    val refundSubmitting: Set<String> = emptySet(),
    val refundError: String? = null,
)

@HiltViewModel
class ServicesViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
) : ViewModel() {
    val uiState = MutableStateFlow(ServicesUiState())

    init {
        loadStock()
        loadRefunds()
        viewModelScope.launch {
            sse.events.collect { event ->
                if (event.type == "voucher_refund_pending" || event.type == "voucher_refund_completed") {
                    loadRefunds()
                }
                if (event.type == "voucher_purchased") {
                    val purchaseId = try {
                        event.data?.jsonObject?.get("purchase_id")?.jsonPrimitive?.content
                    } catch (e: Exception) {
                        null
                    }
                    val current = uiState.value.purchase
                    if (current != null && purchaseId == current.id) {
                        val code = try {
                            event.data?.jsonObject?.get("voucher_code")?.jsonPrimitive?.content
                        } catch (e: Exception) {
                            null
                        }
                        uiState.value = uiState.value.copy(
                            purchase = current.copy(status = "completed", voucherCode = code),
                        )
                        loadStock()
                    }
                }
            }
        }
        // Countdown + status polling (fallback when SSE misses the event).
        viewModelScope.launch {
            var ticks = 0
            while (true) {
                delay(1_000)
                ticks++
                val p = uiState.value.purchase ?: continue
                if (p.status != "pending") continue
                val left = secondsUntil(p.expiresAt)
                uiState.value = uiState.value.copy(secondsLeft = left)
                if (left <= 0) {
                    uiState.value = uiState.value.copy(
                        purchase = null,
                        purchaseError = "Payment expired. Please try again.",
                    )
                    continue
                }
                if (ticks % 5 == 0) {
                    try {
                        val status = api.getVoucherPurchase(p.id)
                        if (status.status == "completed") {
                            uiState.value = uiState.value.copy(purchase = status)
                            loadStock()
                        }
                    } catch (e: Exception) {
                        // transient polling error, retry next tick
                    }
                }
            }
        }
    }

    fun loadStock() {
        viewModelScope.launch {
            try {
                val stock = api.getVoucherStock()
                uiState.value = uiState.value.copy(loading = false, error = null, stock = stock)
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(loading = false, error = ApiError.messageOf(e))
            }
        }
    }

    /** Purchases with an active refund lifecycle (mirrors the web VoucherRefunds panel). */
    fun loadRefunds() {
        viewModelScope.launch {
            try {
                val page = api.getVoucherPurchases()
                val pending = page.items.filter {
                    !it.refundStatus.isNullOrEmpty() && it.refundStatus != "completed"
                }
                uiState.value = uiState.value.copy(refunds = pending)
            } catch (e: Exception) {
                // Silent: the refunds panel simply stays empty on error.
            }
        }
    }

    fun submitRefund(purchaseId: String, invoice: String) {
        val bolt11 = invoice.trim()
        if (bolt11.isEmpty()) {
            uiState.value = uiState.value.copy(refundError = "Paste a Lightning invoice first")
            return
        }
        uiState.value = uiState.value.copy(
            refundSubmitting = uiState.value.refundSubmitting + purchaseId,
            refundError = null,
        )
        viewModelScope.launch {
            try {
                api.submitVoucherRefundInvoice(purchaseId, com.bvb.android.data.model.RefundInvoiceRequest(bolt11))
                loadRefunds()
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(refundError = ApiError.messageOf(e))
            } finally {
                uiState.value = uiState.value.copy(
                    refundSubmitting = uiState.value.refundSubmitting - purchaseId,
                )
            }
        }
    }

    fun buy() {
        uiState.value = uiState.value.copy(purchasing = true, purchaseError = null, sheetDismissed = false)
        viewModelScope.launch {
            try {
                val purchase = api.purchaseVoucher()
                uiState.value = uiState.value.copy(
                    purchasing = false,
                    purchase = purchase,
                    secondsLeft = secondsUntil(purchase.expiresAt),
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(purchasing = false, purchaseError = ApiError.messageOf(e))
            }
        }
    }

    fun hideSheet() {
        uiState.value = uiState.value.copy(sheetDismissed = true)
    }

    fun showSheet() {
        uiState.value = uiState.value.copy(sheetDismissed = false)
    }

    fun dismissPurchase() {
        uiState.value = uiState.value.copy(purchase = null, purchaseError = null, sheetDismissed = false)
    }

    private fun secondsUntil(iso: String): Long = try {
        (Instant.parse(iso).toEpochMilli() - System.currentTimeMillis()) / 1000
    } catch (e: Exception) {
        0
    }
}

@Composable
fun ServicesScreen(viewModel: ServicesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadStock() }

    if (state.loading) {
        FullScreenLoading()
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        state.error?.let {
            ErrorBanner(it)
            Spacer(Modifier.height(16.dp))
        }

        state.stock?.let { stock ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Image(
                        painter = painterResource(R.drawable.mullvad_logo),
                        contentDescription = "Mullvad VPN",
                        modifier = Modifier
                            .size(100.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Mullvad VPN Voucher", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Get a ${stock.durationMonths}-month Mullvad VPN voucher code. " +
                            "Pay with Lightning, receive the code instantly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%.2f EUR".format(stock.priceEur),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (stock.available > 0) "${stock.available} available" else "Out of stock",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (stock.available > 0) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LoadingButton(
                        onClick = { viewModel.buy() },
                        loading = state.purchasing,
                        enabled = stock.available > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Buy Voucher") }
                }
            }
        }

        state.purchaseError?.let {
            Spacer(Modifier.height(16.dp))
            ErrorBanner(it)
        }

        if (state.refunds.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            VoucherRefundsSection(
                refunds = state.refunds,
                submitting = state.refundSubmitting,
                error = state.refundError,
                onSubmit = { id, invoice -> viewModel.submitRefund(id, invoice) },
            )
        }

        if (state.purchase?.status == "pending" && state.sheetDismissed) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = { viewModel.showSheet() }, modifier = Modifier.fillMaxWidth()) {
                Text("Show pending invoice (${formatCountdown(state.secondsLeft)})")
            }
        }
    }

    state.purchase?.let { purchase ->
        if (purchase.status == "completed") {
            VoucherCodeSheet(
                purchase = purchase,
                onDismiss = { viewModel.dismissPurchase() },
                onCopy = { text, label ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(label, text))
                    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                },
            )
        } else if (!state.sheetDismissed) {
            purchase.paymentRequest?.let { bolt11 ->
                InvoiceSheet(
                    title = "Pay with Lightning",
                    bolt11 = bolt11,
                    amountSat = purchase.amountSats,
                    expiresAt = purchase.expiresAt,
                    onDismiss = { viewModel.hideSheet() },
                    dismissLabel = "Close (waiting for payment)",
                    // Same summary as the web app's purchase modal.
                    summaryRows = listOf(
                        "Price" to "%.2f %s".format(purchase.amountFiat, purchase.currency),
                        "Amount" to "%,d sats".format(purchase.amountSats),
                        "BTC Rate" to "%,.0f %s".format(purchase.btcPrice, purchase.currency),
                    ),
                    refText = "Ref: ${purchase.id}",
                )
            }
        }
    }
}

@Composable
private fun VoucherRefundsSection(
    refunds: List<VoucherPurchase>,
    submitting: Set<String>,
    error: String?,
    onSubmit: (id: String, invoice: String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Voucher refunds", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "A payment for one of your vouchers arrived after it had expired. " +
                    "Provide a Lightning invoice for the exact amount to receive your refund.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(it)
            }
            refunds.forEach { p ->
                Spacer(Modifier.height(12.dp))
                VoucherRefundCard(
                    purchase = p,
                    submitting = p.id in submitting,
                    onSubmit = { invoice -> onSubmit(p.id, invoice) },
                )
            }
        }
    }
}

@Composable
private fun VoucherRefundCard(
    purchase: VoucherPurchase,
    submitting: Boolean,
    onSubmit: (invoice: String) -> Unit,
) {
    val status = purchase.refundStatus ?: ""
    val amount = if (purchase.refundAmountSats > 0) purchase.refundAmountSats else purchase.amountSats
    val canSubmit = status == "pending_invoice" || status == "failed"
    var invoice by remember(purchase.id) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("%,d sats".format(amount), style = MaterialTheme.typography.titleSmall)
            Text(
                when (status) {
                    "pending_invoice" -> "Invoice needed"
                    "processing" -> "Processing"
                    "failed" -> "Failed — try again"
                    else -> status
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (status == "failed") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Ref: ${purchase.id}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        purchase.refundError?.takeIf { it.isNotEmpty() && status == "failed" }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (canSubmit) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = invoice,
                onValueChange = { invoice = it },
                label = { Text("BOLT11 invoice for exactly %,d sats".format(amount)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            LoadingButton(
                onClick = { onSubmit(invoice) },
                loading = submitting,
                enabled = invoice.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Get refund") }
        }
        if (status == "processing") {
            Spacer(Modifier.height(8.dp))
            Text(
                "Your refund is being paid…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoucherCodeSheet(
    purchase: VoucherPurchase,
    onDismiss: () -> Unit,
    onCopy: (text: String, label: String) -> Unit,
) {
    AppSheet(title = "", onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("Payment Confirmed", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                purchase.voucherCode ?: "-",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Redeem your voucher at mullvad.net. The code was also sent via Telegram.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onCopy(purchase.voucherCode ?: "", "Voucher code") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy code") }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done") }
            Spacer(Modifier.height(8.dp))
            Text(
                "Ref: ${purchase.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
