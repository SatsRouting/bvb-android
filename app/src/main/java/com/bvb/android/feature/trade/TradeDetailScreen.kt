package com.bvb.android.feature.trade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bvb.android.data.model.OrderInvoiceResponse
import com.bvb.android.data.model.TradeResponse
import com.bvb.android.ui.components.AppSheet
import com.bvb.android.ui.components.AppSnackbar
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.PaymentSummaryCard
import com.bvb.android.ui.components.QrCode
import com.bvb.android.ui.components.StatusChip
import com.bvb.android.ui.components.WaitingSpinner
import com.bvb.android.ui.components.formatDate
import com.bvb.android.feature.chat.ChatPrefill
import com.bvb.android.ui.components.formatFiat
import com.bvb.android.ui.components.formatSats
import com.bvb.android.ui.components.formatTimeMmSs
import com.bvb.android.ui.components.explorerTxUrl
import com.bvb.android.ui.components.openUrl
import com.bvb.android.ui.components.passwordContentType
import com.bvb.android.ui.components.paymentMethodLabel
import com.bvb.android.ui.theme.OrderBuy
import com.bvb.android.ui.theme.OrderSell
import com.bvb.android.ui.theme.SuccessGreen
import com.bvb.android.ui.theme.WarningAmber
import java.time.Instant
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeDetailScreen(
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TradeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Matcher deposit invoice in a bottom sheet (kept from the previous UX).
    if (state.showDepositSheet) {
        state.depositInvoice?.let { inv ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { viewModel.dismissDepositInvoice() },
                sheetState = sheetState,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp),
                ) {
                    MatcherInvoiceSheetContent(
                        trade = state.trade,
                        orderType = state.orderType,
                        invoice = inv,
                        onExpired = { viewModel.onDepositInvoiceExpired() },
                    )
                }
            }
        }
    }

    state.askPasswordFor?.let { action ->
        PasswordPromptSheet(
            action = action,
            biometric = viewModel.biometric,
            onConfirm = { pwd -> viewModel.executeWithPassword(action, pwd) },
            onDismiss = { viewModel.dismissPasswordPrompt() },
        )
    }

    // Transient trade results go to the global snackbar instead of a popup.
    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let { msg ->
            val txid = state.lastTxid
            AppSnackbar.show(if (txid != null) "$msg (txid: ${txid.take(16)}…)" else msg)
            viewModel.clearInfo()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trade Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val chatEnabled = state.trade?.status !in listOf(null, "matched")
                    IconButton(onClick = { onOpenChat(viewModel.tradeId) }, enabled = chatEnabled) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat")
                    }
                },
            )
        }
    ) { padding ->
        if (state.loading) {
            FullScreenLoading()
            return@Scaffold
        }
        val trade = state.trade ?: run {
            Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorBanner(state.error ?: "Trade not found")
            }
            return@Scaffold
        }
        val myId = viewModel.session.userId.orEmpty()
        val iAmBuyer = trade.buyerId == myId

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

            TradeDetailsTable(trade, iAmBuyer)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Actions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            // Matched state, split by role like the web app.
            if (trade.status == "matched") {
                MatchedSection(state, iAmBuyer, myId, viewModel)
            }

            // Countdown + penalty warning for funded / payment_sent.
            CountdownSection(trade, state.buyerTimeRemaining, state.sellerTimeRemaining)

            // Funded: payment instruction notification boxes.
            if (trade.status == "funded") {
                if (!iAmBuyer) {
                    NotificationBox(
                        title = "Action required: Provide payment details",
                        text = "The escrow is fully funded. Send the buyer your payment instructions for the fiat transfer.",
                        buttonLabel = "Send payment instructions",
                        onButtonClick = {
                            // Prefill the chat with the same template as the web app.
                            ChatPrefill.set(
                                viewModel.tradeId,
                                "Hello, here are my payment details:\n\n" +
                                    "Payment methods: ${trade.paymentMethod.joinToString(", ") { paymentMethodLabel(it) }}\n" +
                                    "Amount: ${formatFiat(trade.finalFiatAmount, trade.currencyCode)}\n\n" +
                                    "Insert your bank account/payment details here\n\n" +
                                    "Please confirm once you have made the payment.",
                            )
                            onOpenChat(viewModel.tradeId)
                        },
                    )
                } else {
                    NotificationBox(
                        title = "Waiting for payment instructions",
                        text = "The escrow is fully funded. You should receive the fiat payment details from the seller soon.",
                        buttonLabel = "Contact seller",
                        onButtonClick = { onOpenChat(viewModel.tradeId) },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Status notes, mirroring the web app.
            when {
                trade.status == "payment_sent" && iAmBuyer -> {
                    StatusNote("You have reported sending the payment. Waiting for seller confirmation.")
                }
                trade.status == "disputed" -> {
                    StatusNote("This trade is currently in dispute. Awaiting resolution by admin.", WarningAmber)
                }
                trade.status == "completed" -> {
                    StatusNote("This trade has been completed successfully. Funds have been released.", SuccessGreen)
                }
                trade.status == "claimed" -> {
                    StatusNote("Funds have been claimed and released on-chain.", SuccessGreen)
                }
            }

            TradeActions(
                trade = trade,
                iAmBuyer = iAmBuyer,
                myId = myId,
                actionInProgress = state.actionInProgress,
                viewModel = viewModel,
            )
        }
    }
}

/** Field rows matching the web app's "Trade Details" table. */
@Composable
private fun TradeDetailsTable(trade: TradeResponse, iAmBuyer: Boolean) {
    val context = LocalContext.current

    Text("Trade Details", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    DetailRow("Order ID") {
        Text(trade.orderId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    DetailRow("Trade ID") {
        Text(trade.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    DetailRow("Role") {
        Text(
            if (iAmBuyer) "BUYER" else "SELLER",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (iAmBuyer) OrderBuy else OrderSell,
        )
    }
    DetailRow("Counterparty") {
        Text(
            if (iAmBuyer) trade.sellerAvatar else trade.buyerAvatar,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    DetailRow("Status") { StatusChip(trade.status) }
    DetailRow("Bitcoin Amount") { DetailText(formatSats(trade.amount)) }
    DetailRow("Fiat Value") { DetailText(formatFiat(trade.finalFiatAmount, trade.currencyCode)) }
    DetailRow("BTC Price") { DetailText(formatFiat(trade.price, trade.currencyCode)) }
    DetailRow("Payment Methods") {
        DetailText(trade.paymentMethod.joinToString(", ") { paymentMethodLabel(it) })
    }
    DetailRow("Created on") { DetailText(formatDate(trade.createdAt)) }
    trade.completedAt?.let {
        DetailRow("Completed on") { DetailText(formatDate(it)) }
    }
    trade.releaseTxid?.takeIf { it.isNotEmpty() }?.let { txid ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                "Release TX",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                txid,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    openUrl(context, explorerTxUrl(trade.settlementChain, txid, blindingData = trade.releaseBlindingData))
                },
            )
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

/** Info box with icon, like the web app's trade notifications. */
@Composable
private fun NotificationBox(
    title: String,
    text: String,
    buttonLabel: String? = null,
    onButtonClick: (() -> Unit)? = null,
) {
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
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (buttonLabel != null && onButtonClick != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onButtonClick) { Text(buttonLabel) }
            }
        }
    }
}

@Composable
private fun StatusNote(text: String, color: androidx.compose.ui.graphics.Color? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
}

/** Countdown plus penalty warning for funded / payment_sent, like the web. */
@Composable
private fun CountdownSection(trade: TradeResponse, buyerRemaining: Long?, sellerRemaining: Long?) {
    val (label, remaining, warning) = when (trade.status) {
        "funded" -> Triple(
            "Buyer payment expires in",
            buyerRemaining,
            "When time expires, the buyer will be penalized if payment is not confirmed.",
        )
        "payment_sent" -> Triple(
            "Seller confirmation expires in",
            sellerRemaining,
            "When time expires, a dispute will be automatically opened if receipt is not confirmed.",
        )
        else -> return
    }
    if (remaining == null || remaining <= 0) return

    val h = remaining / 3600
    val m = (remaining % 3600) / 60
    val s = remaining % 60
    Text(
        "$label %02d:%02d:%02d".format(h, m, s),
        style = MaterialTheme.typography.titleSmall,
        color = WarningAmber,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .background(WarningAmber.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = WarningAmber,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(warning, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * Matched state, split by role like the web app's renderMatchedState():
 * creator waits, matcher pays (invoice in the bottom sheet) or sees
 * paid/expired states.
 */
@Composable
private fun MatchedSection(
    state: TradeDetailUiState,
    iAmBuyer: Boolean,
    myId: String,
    viewModel: TradeDetailViewModel,
) {
    val trade = state.trade ?: return
    val isMatcher = when (state.orderType) {
        "sell" -> trade.buyerId == myId
        "buy" -> trade.sellerId == myId
        else -> false
    }

    when {
        !isMatcher -> {
            NotificationBox(
                title = "Waiting for counterparty deposit",
                text = "The counterparty needs to pay their deposit. Once confirmed, the escrow will be funded automatically.",
            )
            Spacer(Modifier.height(8.dp))
            WaitingSpinner("")
        }
        state.invoicePaid -> {
            WaitingSpinner("Escrow funding in progress")
            Spacer(Modifier.height(4.dp))
            Text(
                "The deposits are being moved to the escrow multisig. This may take a few minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        state.invoiceLoading -> WaitingSpinner("Loading invoice…")
        state.depositInvoice != null -> {
            NotificationBox(
                title = "Deposit required",
                text = "Pay your Lightning deposit to fund the trade escrow.",
                buttonLabel = "Show deposit invoice",
                onButtonClick = { viewModel.openDepositSheet() },
            )
        }
        else -> {
            NotificationBox(
                title = "Invoice expired",
                text = "The deposit was not paid in time. The match has been cancelled and the order is available again on the marketplace.",
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * Bottom sheet content for the matcher's deposit invoice, same layout as the
 * web app's matched-state invoice (fee summary, countdown, QR, waiting note).
 */
@Composable
private fun MatcherInvoiceSheetContent(
    trade: TradeResponse?,
    orderType: String?,
    invoice: OrderInvoiceResponse,
    onExpired: () -> Unit,
) {
    val context = LocalContext.current
    val isTakingSell = orderType == "sell"

    var remaining by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(invoice.expiresAt) {
        while (true) {
            val left = try {
                Instant.parse(invoice.expiresAt).epochSecond - Instant.now().epochSecond
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
    trade?.let {
        Text(
            if (isTakingSell) {
                "To buy ${formatSats(it.amount)} you must deposit ${formatSats(it.buyerDeposit)} (10%) as a guarantee."
            } else {
                "Lock the security Bitcoin deposit to sell ${formatSats(it.amount)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(12.dp))

    PaymentSummaryCard(
        depositLabel = "Deposit (${if (isTakingSell) "10%" else "100%"})",
        depositSat = invoice.depositAmount,
        serviceFeeSat = invoice.serviceFee,
        totalSat = invoice.invoice.invoiceAmountSat,
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

@Composable
private fun TradeActions(
    trade: TradeResponse,
    iAmBuyer: Boolean,
    myId: String,
    actionInProgress: Boolean,
    viewModel: TradeDetailViewModel,
) {
    val enabled = !actionInProgress
    var confirmPayment by remember { mutableStateOf(false) }

    // Buyer confirms the fiat payment after an explicit warning, like the web.
    if (confirmPayment) {
        AppSheet(
            title = "Confirm Fiat Payment",
            onDismiss = { confirmPayment = false },
        ) {
            Text(
                "You are about to confirm that you have sent the payment of " +
                    "${formatFiat(trade.finalFiatAmount, trade.currencyCode)} to the seller."
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Important: only confirm after you have actually completed the payment " +
                    "using the agreed method (${trade.paymentMethod.joinToString(", ") { paymentMethodLabel(it) }}).",
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    confirmPayment = false
                    viewModel.buyerPaymentSent()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Confirm Payment Sent") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirmPayment = false },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }

    Column {
        when (trade.status) {
            "funded" -> {
                if (iAmBuyer) {
                    Button(
                        onClick = { confirmPayment = true },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Confirm Fiat Payment") }
                }
                CancelSection(trade, iAmBuyer, enabled, viewModel)
            }
            "payment_sent" -> {
                if (!iAmBuyer) {
                    Button(
                        onClick = { viewModel.requestPasswordFor(TradeAction.SELLER_CONFIRM_PAYMENT) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Confirm Receipt & Release") }
                }
            }
            "penalized" -> {
                // Seller can claim after buyer payment timeout.
                if (trade.buyerPaymentPenalty && !iAmBuyer && trade.releaseTxid.isNullOrEmpty()) {
                    Button(
                        onClick = { viewModel.requestPasswordFor(TradeAction.CLAIM_BUYER_TIMEOUT) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Claim Funds") }
                }
            }
            "resolved" -> {
                if (trade.disputeWinnerId == myId && trade.releaseTxid.isNullOrEmpty()) {
                    Button(
                        onClick = { viewModel.requestPasswordFor(TradeAction.CLAIM_DISPUTE) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Claim Funds") }
                }
            }
            "completed", "claimed" -> {
                if (!trade.releaseTxid.isNullOrEmpty()) {
                    Text(
                        "Funds released on-chain",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CancelSection(
    trade: TradeResponse,
    iAmBuyer: Boolean,
    enabled: Boolean,
    viewModel: TradeDetailViewModel,
) {
    val myConsent = if (iAmBuyer) trade.buyerCancelConsent else trade.sellerCancelConsent
    val theirConsent = if (iAmBuyer) trade.sellerCancelConsent else trade.buyerCancelConsent

    Spacer(Modifier.height(12.dp))
    when {
        theirConsent && !myConsent -> {
            Text(
                "The counterparty asked to cancel this trade by mutual consent.",
                style = MaterialTheme.typography.bodyMedium,
                color = WarningAmber,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.requestPasswordFor(TradeAction.CANCEL_CONFIRM) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Accept Cancellation") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.rejectCancellation() },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reject Cancellation") }
        }
        myConsent -> {
            Text(
                "Cancellation requested. Waiting for the counterparty to accept.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            OutlinedButton(
                onClick = { viewModel.requestPasswordFor(TradeAction.CANCEL_REQUEST) },
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel Trade") }
        }
    }
}

@Composable
private fun PasswordPromptSheet(
    action: TradeAction,
    biometric: com.bvb.android.core.security.BiometricUnlock,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val (title, description) = when (action) {
        TradeAction.SELLER_CONFIRM_PAYMENT -> "Confirm Receipt & Release" to
            "Confirm that you received the fiat payment from the buyer. This will release the Bitcoin to the buyer's wallet. This action is irreversible."
        TradeAction.CLAIM_BUYER_TIMEOUT -> "Claim Funds (Buyer Timeout)" to
            "The buyer did not send payment in time. Sign to claim the escrowed funds to your wallet."
        TradeAction.CLAIM_DISPUTE -> "Claim Dispute Funds" to
            "The dispute has been resolved in your favor. Sign to claim the escrowed funds to your wallet."
        TradeAction.CANCEL_REQUEST -> "Request Cancellation" to
            "Sign a cancellation request. The counterparty must also sign for the cancellation to complete and refund both parties."
        TradeAction.CANCEL_CONFIRM -> "Confirm Cancellation" to
            "Sign to confirm the cancellation. Both deposits will be refunded to their respective owners."
    }
    AppSheet(title = title, onDismiss = onDismiss) {
        val bioAction = com.bvb.android.ui.components.rememberBiometricAction(
            biometric, title, "Authenticate to sign this action",
        )
        Text(description)
        Spacer(Modifier.height(16.dp))
        if (bioAction != null) {
            Button(
                onClick = { bioAction { onConfirm(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Confirm") }
        } else {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().passwordContentType(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onConfirm(password) },
                enabled = password.length >= 8,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Confirm") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancel") }
    }
}
