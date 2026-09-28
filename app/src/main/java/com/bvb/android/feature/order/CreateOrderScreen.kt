package com.bvb.android.feature.order

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.R
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.CreateOrderRequest
import com.bvb.android.data.model.LightningInvoice
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.LoadingButton
import com.bvb.android.ui.components.PaymentMethodIcons
import com.bvb.android.ui.components.PaymentSummaryCard
import com.bvb.android.ui.components.QrCode
import com.bvb.android.ui.components.SmallCenteredNote
import com.bvb.android.ui.components.StepIconHeader
import com.bvb.android.ui.components.WaitingSpinner
import com.bvb.android.ui.components.formatTimeMmSs
import com.bvb.android.ui.components.noAutofill
import com.bvb.android.ui.components.passwordContentType
import com.bvb.android.ui.theme.OrderBuy
import com.bvb.android.ui.theme.OrderSell
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Same canonical codes and labels as the web CreateOrderModal. */
val PAYMENT_METHODS = listOf(
    "amazon" to "Amazon",
    "paysend" to "Paysend",
    "wise" to "Wise",
    "revolut" to "Revolut",
    "yuh" to "Yuh",
    "zen" to "Zen",
    "usdt" to "USDT",
    "usdtliquid" to "USDT Liquid",
    "sepa" to "SEPA",
    "instantsepa" to "InstantSepa",
)

private val CURRENCIES = listOf("EUR", "USD", "CHF", "GBP", "CAD", "AUD")

data class CreateOrderUiState(
    val submitting: Boolean = false,
    val error: String? = null,
    val orderId: String? = null,
    val invoice: LightningInvoice? = null,
    val invoiceExpiresAt: String? = null,
    val depositAmount: Long = 0,
    val serviceFee: Long = 0,
    val orderType: String = "sell",
    /** Deposit paid, escrow being funded (web app's step 3). */
    val paid: Boolean = false,
    /** Order reached "open": show the success state and leave. */
    val orderOpen: Boolean = false,
    /** Current BTC price for the selected currency (estimated sats hint). */
    val btcPrice: Double? = null,
)

@HiltViewModel
class CreateOrderViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
    private val session: SessionManager,
    val biometric: com.bvb.android.core.security.BiometricUnlock,
) : ViewModel() {
    val uiState = MutableStateFlow(CreateOrderUiState())

    init {
        // Same transitions as the web app's CreateOrderModal: the paid deposit
        // moves to "escrow creation", and the order turning open ends the flow.
        viewModelScope.launch {
            sse.events.collect { event ->
                val orderId = uiState.value.orderId ?: return@collect
                val data = event.data?.jsonObject
                val evOrderId = data?.get("order_id")?.jsonPrimitive?.content
                if (evOrderId != orderId) return@collect
                when (event.type) {
                    "payment_verified", "order_escrow_funding" ->
                        uiState.update { it.copy(paid = true) }
                    "order_status_changed" -> {
                        val newStatus = data["new_status"]?.jsonPrimitive?.content
                        if (newStatus == "open") {
                            uiState.update { it.copy(paid = true, orderOpen = true) }
                        }
                    }
                    "order_corrupted" -> uiState.update {
                        it.copy(
                            invoice = null,
                            error = "Order could not be fully processed. Visit the Recovery page to unlock your funds.",
                        )
                    }
                }
            }
        }
        // Fallback polling in case an SSE event is missed (like the web app).
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                val state = uiState.value
                val orderId = state.orderId ?: continue
                if (state.orderOpen || state.invoice == null) continue
                try {
                    val order = api.getOrder(orderId)
                    when {
                        order.status == "open" ->
                            uiState.update { it.copy(paid = true, orderOpen = true) }
                        order.status == "pending" && !order.fundingTxid.isNullOrEmpty() ->
                            uiState.update { it.copy(paid = true) }
                    }
                } catch (e: Exception) {
                    // Transient network error; the next tick retries.
                }
            }
        }
    }

    /** Fresh state when the sheet is opened again. */
    fun reset() {
        uiState.value = CreateOrderUiState()
    }

    fun loadPrice(currency: String) {
        viewModelScope.launch {
            val price = try {
                api.getBitcoinPrice(currency).price
            } catch (e: Exception) {
                null
            }
            uiState.update { it.copy(btcPrice = price) }
        }
    }

    /** The deposit invoice expired without being paid. */
    fun onInvoiceExpired() {
        uiState.update {
            it.copy(invoice = null, orderId = null, error = "Time expired for invoice payment")
        }
    }

    fun submit(
        type: String,
        fiatAmount: String,
        margin: String,
        methods: List<String>,
        currency: String,
        settlementChain: String,
        password: String,
    ) {
        val amount = fiatAmount.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            uiState.value = uiState.value.copy(error = "Enter a valid fiat amount")
            return
        }
        val marginPct = margin.toDoubleOrNull()
        if (marginPct == null || marginPct < -20 || marginPct > 20) {
            uiState.value = uiState.value.copy(error = "Premium must be between -20% and +20%")
            return
        }
        if (methods.isEmpty()) {
            uiState.value = uiState.value.copy(error = "Select at least one payment method")
            return
        }
        // Like the web app: the password must be typed every time, it
        // authorizes signing the escrow transaction.
        if (password.isBlank()) {
            uiState.value = uiState.value.copy(error = "Enter your password to sign the escrow transaction")
            return
        }
        uiState.value = uiState.value.copy(submitting = true, error = null)
        viewModelScope.launch {
            try {
                val resp = api.createOrder(
                    CreateOrderRequest(
                        type = type,
                        fiatAmount = amount,
                        marginPercentage = marginPct,
                        paymentMethod = methods,
                        currencyCode = currency,
                        settlementChain = settlementChain,
                        password = password,
                    )
                )
                session.sessionPassword = password
                uiState.update {
                    it.copy(
                        submitting = false,
                        orderId = resp.order.id,
                        invoice = resp.invoice,
                        invoiceExpiresAt = resp.order.expiresAt,
                        depositAmount = resp.order.depositAmount,
                        serviceFee = resp.order.serviceFee,
                        orderType = resp.order.type,
                    )
                }
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(submitting = false, error = ApiError.messageOf(e))
            }
        }
    }
}

/**
 * Order creation in a bottom sheet, mirroring the web CreateOrderModal:
 * form (with reset trash button) -> deposit invoice -> payment confirmed ->
 * order created.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateOrderSheet(
    onDismiss: () -> Unit,
    viewModel: CreateOrderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The view model outlives the sheet (it is scoped to the activity), so
    // start from a clean slate every time the sheet is opened.
    LaunchedEffect(Unit) { viewModel.reset() }

    // Once the order reaches "open", show the success state briefly and
    // leave, like the web app's CreateOrderModal.
    LaunchedEffect(state.orderOpen) {
        if (state.orderOpen) {
            delay(2000)
            onDismiss()
        }
    }

    ModalBottomSheet(
        // Dismissable at any step: a pending order (and its invoice) stays
        // reachable from My Orders.
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .imePadding(),
        ) {
            val invoice = state.invoice
            if (invoice != null) {
                CreateOrderPaymentFlow(
                    state = state,
                    invoice = invoice,
                    onExpired = { viewModel.onInvoiceExpired() },
                    onPayLater = onDismiss,
                )
            } else {
                CreateOrderForm(state = state, viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateOrderForm(state: CreateOrderUiState, viewModel: CreateOrderViewModel) {
    var type by remember { mutableStateOf("") }
    var fiatAmount by remember { mutableStateOf("") }
    var margin by remember { mutableStateOf("0") }
    var currency by remember { mutableStateOf("EUR") }
    // Settlement chain, mirroring the web CreateOrderModal which defaults to Bitcoin.
    var settlementChain by remember { mutableStateOf("bitcoin") }
    var methods by remember { mutableStateOf(listOf<String>()) }
    var password by remember { mutableStateOf("") }
    var showMethodsDropdown by remember { mutableStateOf(false) }
    var showCurrencyDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(currency) { viewModel.loadPrice(currency) }

    val estimatedSats = state.btcPrice?.let { price ->
        fiatAmount.toDoubleOrNull()?.let { fiat ->
            if (price > 0) ceil(fiat / price * 100_000_000).toLong() else null
        }
    }
    val depositPercentage = if (type == "sell") 100 else 10

    // Header: title + trash button resetting all fields, like the web modal.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Create Order", style = MaterialTheme.typography.titleLarge)
        IconButton(
            onClick = {
                type = ""
                fiatAmount = ""
                margin = "0"
                currency = "EUR"
                settlementChain = "bitcoin"
                methods = emptyList()
                password = ""
                showMethodsDropdown = false
            },
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Reset all fields",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    state.error?.let {
        ErrorBanner(it)
        Spacer(Modifier.height(12.dp))
    }

    // BUY / SELL selector with the web app's buy/sell accent colors.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TypeButton("BUY", OrderBuy, selected = type == "buy", Modifier.weight(1f)) { type = "buy" }
        TypeButton("SELL", OrderSell, selected = type == "sell", Modifier.weight(1f)) { type = "sell" }
    }
    Spacer(Modifier.height(12.dp))

    // Settlement chain selector with the same bitcoin.png / liquid.png logos as
    // the web CreateOrderModal (segmented control, default Bitcoin).
    Text("Settlement network", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SettlementOption(
            label = "Bitcoin",
            logo = R.drawable.bitcoin,
            selected = settlementChain == "bitcoin",
            modifier = Modifier.weight(1f),
        ) { settlementChain = "bitcoin" }
        SettlementOption(
            label = "Liquid",
            logo = R.drawable.liquid,
            selected = settlementChain == "liquid",
            modifier = Modifier.weight(1f),
        ) { settlementChain = "liquid" }
    }
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.4f)) {
            OutlinedTextField(
                value = fiatAmount,
                onValueChange = { fiatAmount = it },
                label = { Text("Amount ($currency)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().noAutofill(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            estimatedSats?.let {
                Text(
                    "≈ %,d sats".format(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(Modifier.weight(1f).padding(top = 8.dp)) {
            OutlinedButton(
                onClick = { showCurrencyDropdown = true },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(currency)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(
                expanded = showCurrencyDropdown,
                onDismissRequest = { showCurrencyDropdown = false },
            ) {
                CURRENCIES.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        trailingIcon = if (currency == code) {
                            { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = {
                            currency = code
                            showCurrencyDropdown = false
                        },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    Text("Premium over Market (%)", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    PremiumSelector(value = margin, onValueChange = { margin = it })
    Text(
        "From -20% to +20% compared to market price",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(12.dp))

    Text("Fiat Payment Method(s)", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))

    // Selected methods as removable tags, like the web modal.
    if (methods.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            methods.forEach { value ->
                val label = PAYMENT_METHODS.firstOrNull { it.first == value }?.second ?: value
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaymentMethodIcons(listOf(value), sizeDp = 18)
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        IconButton(
                            onClick = { methods = methods - value },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $label",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    Box {
        OutlinedButton(
            onClick = { showMethodsDropdown = !showMethodsDropdown },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Select payment methods", modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(
            expanded = showMethodsDropdown,
            onDismissRequest = { showMethodsDropdown = false },
        ) {
            PAYMENT_METHODS.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { PaymentMethodIcons(listOf(value), sizeDp = 20) },
                    trailingIcon = if (value in methods) {
                        { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        methods = if (value in methods) methods - value else methods + value
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    val bioAction = com.bvb.android.ui.components.rememberBiometricAction(
        viewModel.biometric,
        "Sign escrow transaction",
        "Authenticate to sign this order",
    )
    if (bioAction == null) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (to sign the escrow transaction)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().passwordContentType(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
    Spacer(Modifier.height(20.dp))

    // Every field is mandatory: type, amount, premium, methods. The password is
    // required only when the App lock is off; otherwise biometrics supplies it
    // on submit (the CREATE ORDER button itself triggers authentication).
    val formValid = type.isNotEmpty() &&
        (fiatAmount.toDoubleOrNull() ?: 0.0) > 0 &&
        margin.toDoubleOrNull() != null &&
        methods.isNotEmpty() &&
        (bioAction != null || password.isNotBlank())

    LoadingButton(
        onClick = {
            if (bioAction != null) {
                bioAction { pw ->
                    viewModel.submit(type, fiatAmount, margin, methods, currency, settlementChain, pw)
                }
            } else {
                viewModel.submit(type, fiatAmount, margin, methods, currency, settlementChain, password)
            }
        },
        loading = state.submitting,
        enabled = formValid,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("CREATE ORDER", fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(12.dp))

    // Deposit info footer, like the web modal.
    Text(
        buildString {
            append("Required deposit: $depositPercentage% of trade amount")
            estimatedSats?.let {
                append(" ≈ %,d sats".format(ceil(it * depositPercentage / 100.0).toLong()))
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    state.btcPrice?.let { price ->
        val priceWithMargin = price * (1 + (margin.toDoubleOrNull() ?: 0.0) / 100)
        Text(
            "%,.0f %s/BTC".format(priceWithMargin, currency),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Premium-over-market selector, mirroring the web .margin-selector: an editable
 * value box (clamped to [-20, +20]) plus a gradient slider (buy at the edges,
 * sell at the center) with a 0% center tick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumSelector(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val min = -20f
    val max = 20f
    val current = (value.toFloatOrNull() ?: 0f).coerceIn(min, max)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { raw ->
                when {
                    raw.isEmpty() || raw == "-" -> onValueChange(raw)
                    else -> {
                        val n = raw.toFloatOrNull()
                        if (n != null) {
                            when {
                                n < min -> onValueChange(min.toInt().toString())
                                n > max -> onValueChange(max.toInt().toString())
                                else -> onValueChange(raw) // keep exactly what was typed
                            }
                        }
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.width(88.dp).noAutofill(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Slider(
            value = current,
            onValueChange = { v ->
                val rounded = (v * 10f).roundToInt() / 10f
                onValueChange(if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString())
            },
            valueRange = min..max,
            modifier = Modifier.weight(1f),
            track = {
                Box(
                    Modifier.fillMaxWidth().height(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Thin gradient track: buy at the edges -> sell at center -> buy.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.horizontalGradient(listOf(OrderBuy, OrderSell, OrderBuy)),
                            ),
                    )
                    // Center tick marking 0%.
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(12.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
                    )
                }
            },
            thumb = {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, OrderSell, CircleShape),
                )
            },
        )
    }
}

/** Logo-based settlement selector button, mirroring the web .settlement-option. */
@Composable
private fun SettlementOption(
    label: String,
    logo: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) accent else MaterialTheme.colorScheme.outline,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier,
    ) {
        Image(
            painter = painterResource(logo),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TypeButton(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, accent),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
            contentColor = accent,
        ),
        modifier = modifier,
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/**
 * Post-creation payment steps, mirroring the web app's CreateOrderModal:
 * fee summary + invoice -> "Payment Confirmed" (escrow creation) ->
 * "Order Created Successfully".
 */
@Composable
private fun CreateOrderPaymentFlow(
    state: CreateOrderUiState,
    invoice: LightningInvoice,
    onExpired: () -> Unit,
    onPayLater: () -> Unit,
) {
    val context = LocalContext.current

    if (state.orderOpen) {
        StepIconHeader(Icons.Default.CheckCircle, "Order Created Successfully")
        Spacer(Modifier.height(6.dp))
        SmallCenteredNote("Your order is now live on the marketplace.")
        return
    }

    if (state.paid) {
        StepIconHeader(Icons.Default.Check, "Payment Confirmed")
        Spacer(Modifier.height(6.dp))
        SmallCenteredNote("Creating escrow and securing funds in multisig…")
        Spacer(Modifier.height(20.dp))
        WaitingSpinner("Waiting for on-chain confirmation")
        Spacer(Modifier.height(4.dp))
        SmallCenteredNote("Do not close this page until the process is complete")
        return
    }

    var remaining by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(state.invoiceExpiresAt) {
        while (true) {
            val left = try {
                Instant.parse(state.invoiceExpiresAt ?: "").epochSecond - Instant.now().epochSecond
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
        if (state.orderType == "buy") "Make a deposit to buy" else "Deposit the Bitcoin to sell",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))

    PaymentSummaryCard(
        depositLabel = "Deposit (${if (state.orderType == "buy") "10%" else "100%"})",
        depositSat = state.depositAmount,
        serviceFeeSat = state.serviceFee,
        totalSat = invoice.invoiceAmountSat,
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
        QrCode(content = invoice.bolt11.uppercase())
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("invoice", invoice.bolt11))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Copy invoice") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onPayLater,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pay later (order stays pending)") }
    Spacer(Modifier.height(12.dp))
    WaitingSpinner("Waiting for payment confirmation…")
    Spacer(Modifier.height(4.dp))
    SmallCenteredNote("Do not close this page until payment is confirmed")
}
