package com.bvb.android.feature.trade

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.session.SessionManager
import com.bvb.android.core.sse.SseClient
import com.bvb.android.data.model.OrderInvoiceResponse
import com.bvb.android.data.model.PasswordRequest
import com.bvb.android.data.model.TradeResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TradeDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val actionInProgress: Boolean = false,
    val trade: TradeResponse? = null,
    /** Type of the originating order; decides who the matcher is. */
    val orderType: String? = null,
    val buyerTimeRemaining: Long? = null,
    val sellerTimeRemaining: Long? = null,
    val depositInvoice: OrderInvoiceResponse? = null,
    /** Matcher deposit already paid (invoice endpoint returns 404). */
    val invoicePaid: Boolean = false,
    val invoiceLoading: Boolean = false,
    val showDepositSheet: Boolean = false,
    val lastTxid: String? = null,
    val infoMessage: String? = null,
    val askPasswordFor: TradeAction? = null,
)

enum class TradeAction {
    SELLER_CONFIRM_PAYMENT,
    CLAIM_BUYER_TIMEOUT,
    CLAIM_DISPUTE,
    CANCEL_REQUEST,
    CANCEL_CONFIRM,
}

@HiltViewModel
class TradeDetailViewModel @Inject constructor(
    private val api: ApiService,
    private val sse: SseClient,
    val session: SessionManager,
    val biometric: com.bvb.android.core.security.BiometricUnlock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val tradeId: String = checkNotNull(savedStateHandle["tradeId"])
    val uiState = MutableStateFlow(TradeDetailUiState())

    init {
        load()
        // Refresh on any SSE event touching this trade; resync after reconnect.
        viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    "trade_update", "trade_expired", "payment_verified",
                    "buyer_payment_deadline_approaching", "seller_confirm_deadline_approaching",
                    "dispute_resolved_in_favor", "dispute_resolved_against" -> {
                        val evTradeId = event.data?.jsonObject?.get("trade_id")?.jsonPrimitive?.content
                        if (evTradeId == null || evTradeId == tradeId) {
                            // Deposit paid: swap the invoice for the
                            // escrow-funding state (like the web app).
                            if (event.type == "payment_verified") {
                                uiState.value = uiState.value.copy(
                                    invoicePaid = true,
                                    depositInvoice = null,
                                    showDepositSheet = false,
                                )
                            }
                            load(silent = true)
                        }
                    }
                    "sse_reconnected" -> load(silent = true)
                }
            }
        }
        // Fallback refresh while matched (waiting for on-chain confirmation),
        // like the web app's 8s interval.
        viewModelScope.launch {
            while (true) {
                delay(8000)
                if (uiState.value.trade?.status == "matched") load(silent = true)
            }
        }
        // Local ticking countdown between server refreshes.
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = uiState.value
                uiState.value = s.copy(
                    buyerTimeRemaining = s.buyerTimeRemaining?.let { if (it > 0) it - 1 else 0 },
                    sellerTimeRemaining = s.sellerTimeRemaining?.let { if (it > 0) it - 1 else 0 },
                )
            }
        }
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                val trade = api.getTrade(tradeId)
                var buyerRemaining = trade.buyerPaymentTimeRemaining
                var sellerRemaining = trade.sellerConfirmTimeRemaining
                if (trade.status == "funded" || trade.status == "payment_sent") {
                    try {
                        val timelock = api.getTimelockStatus(tradeId)
                        buyerRemaining = timelock.buyerPaymentTimeRemaining
                        sellerRemaining = timelock.sellerConfirmTimeRemaining
                    } catch (e: Exception) {
                        // keep values from the trade payload
                    }
                }
                uiState.value = uiState.value.copy(
                    loading = false,
                    trade = trade,
                    buyerTimeRemaining = buyerRemaining,
                    sellerTimeRemaining = sellerRemaining,
                    error = if (silent) uiState.value.error else null,
                )
                // The order type decides who the matcher is (web fetches it too).
                if (uiState.value.orderType == null) {
                    try {
                        val order = api.getOrder(trade.orderId)
                        uiState.value = uiState.value.copy(orderType = order.type)
                    } catch (e: Exception) {
                        // Leave null; matcher-only sections stay hidden.
                    }
                }
                // Matcher of a matched trade: fetch the deposit invoice so the
                // actions area can show pay / paid / expired states (like web).
                val isMatcher = when (uiState.value.orderType) {
                    "sell" -> trade.buyerId == session.userId
                    "buy" -> trade.sellerId == session.userId
                    else -> false
                }
                if (trade.status == "matched" && isMatcher) {
                    loadDepositInvoice()
                } else if (trade.status != "matched") {
                    uiState.value = uiState.value.copy(
                        depositInvoice = null,
                        showDepositSheet = false,
                    )
                }
            } catch (e: Exception) {
                if (!silent) {
                    uiState.value = uiState.value.copy(loading = false, error = ApiError.messageOf(e))
                }
            }
        }
    }

    private suspend fun loadDepositInvoice() {
        if (uiState.value.depositInvoice != null || uiState.value.invoicePaid) return
        uiState.value = uiState.value.copy(invoiceLoading = true)
        try {
            val invoice = api.getTradeInvoice(tradeId)
            uiState.value = uiState.value.copy(
                depositInvoice = invoice,
                invoicePaid = false,
                invoiceLoading = false,
            )
        } catch (e: Exception) {
            // 404 means the invoice was already paid (mirrors the web app).
            val paid = (e as? retrofit2.HttpException)?.code() == 404
            uiState.value = uiState.value.copy(
                depositInvoice = null,
                invoicePaid = paid,
                invoiceLoading = false,
            )
        }
    }

    fun openDepositSheet() {
        uiState.value = uiState.value.copy(showDepositSheet = true)
    }

    fun dismissDepositInvoice() {
        uiState.value = uiState.value.copy(showDepositSheet = false)
    }

    /** The matcher deposit invoice expired: the match is cancelled server-side. */
    fun onDepositInvoiceExpired() {
        uiState.value = uiState.value.copy(
            depositInvoice = null,
            showDepositSheet = false,
            infoMessage = "Invoice expired. The deposit was not paid in time and the order is available again on the marketplace.",
        )
        load(silent = true)
    }

    fun buyerPaymentSent() {
        runAction {
            api.buyerPaymentSent(tradeId)
            uiState.value = uiState.value.copy(infoMessage = "Payment marked as sent. Waiting for the seller to confirm.")
        }
    }

    fun rejectCancellation() {
        runAction {
            api.rejectCancellation(tradeId)
            uiState.value = uiState.value.copy(infoMessage = "Cancellation request rejected.")
        }
    }

    /**
     * Signing actions always prompt for the password explicitly (like the web
     * app): authorizing an escrow signature must be a deliberate user action.
     */
    fun requestPasswordFor(action: TradeAction) {
        uiState.value = uiState.value.copy(askPasswordFor = action)
    }

    fun dismissPasswordPrompt() {
        uiState.value = uiState.value.copy(askPasswordFor = null)
    }

    fun executeWithPassword(action: TradeAction, password: String) {
        uiState.value = uiState.value.copy(askPasswordFor = null)
        val body = PasswordRequest(password)
        runAction {
            when (action) {
                TradeAction.SELLER_CONFIRM_PAYMENT -> {
                    val res = api.sellerConfirmPayment(tradeId, body)
                    uiState.value = uiState.value.copy(
                        lastTxid = res.txid,
                        infoMessage = "Escrow released. Funds are on the way to the buyer.",
                    )
                }
                TradeAction.CLAIM_BUYER_TIMEOUT -> {
                    val res = api.claimBuyerPaymentTimeout(tradeId, body)
                    uiState.value = uiState.value.copy(lastTxid = res.txid, infoMessage = "Funds claimed.")
                }
                TradeAction.CLAIM_DISPUTE -> {
                    val res = api.claimDispute(tradeId, body)
                    uiState.value = uiState.value.copy(lastTxid = res.txid, infoMessage = "Dispute funds claimed.")
                }
                TradeAction.CANCEL_REQUEST -> {
                    api.requestCancellation(tradeId, body)
                    uiState.value = uiState.value.copy(
                        infoMessage = "Cancellation requested. Waiting for the counterparty.",
                    )
                }
                TradeAction.CANCEL_CONFIRM -> {
                    val res = api.confirmCancellation(tradeId, body)
                    uiState.value = uiState.value.copy(
                        lastTxid = res.txid,
                        infoMessage = "Trade cancelled, deposits refunded.",
                    )
                }
            }
            session.sessionPassword = password
        }
    }

    fun clearInfo() {
        uiState.value = uiState.value.copy(infoMessage = null)
    }

    private fun runAction(block: suspend () -> Unit) {
        uiState.value = uiState.value.copy(actionInProgress = true, error = null)
        viewModelScope.launch {
            try {
                block()
                load(silent = true)
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(error = ApiError.messageOf(e))
            } finally {
                uiState.value = uiState.value.copy(actionInProgress = false)
            }
        }
    }
}
