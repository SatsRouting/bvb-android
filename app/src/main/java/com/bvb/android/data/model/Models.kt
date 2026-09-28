package com.bvb.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class LoginRequest(
    @SerialName("avatar_id") val avatarId: String,
    val password: String,
    @SerialName("cf-turnstile-response") val turnstileToken: String? = null,
)

@Serializable
data class LoginResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("avatar_id") val avatarId: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("expires_at") val expiresAt: Long = 0,
    @SerialName("pgp_keys_created") val pgpKeysCreated: Boolean = false,
    @SerialName("has_pgp_keys") val hasPgpKeys: Boolean = false,
    @SerialName("wallet_migrated") val walletMigrated: Boolean = false,
    val mnemonic: String? = null,
    val token: String? = null,
)

@Serializable
data class CreateAvatarRequest(
    val password: String,
    @SerialName("referral_code") val referralCode: String? = null,
    @SerialName("cf-turnstile-response") val turnstileToken: String? = null,
)

@Serializable
data class CreateAvatarResponse(
    val user: CreatedUser,
    val mnemonic: String,
)

@Serializable
data class CreatedUser(
    val id: String,
    @SerialName("avatar_id") val avatarId: String,
    @SerialName("pgp_public_key") val pgpPublicKey: String? = null,
    @SerialName("pgp_key_id") val pgpKeyId: String? = null,
    @SerialName("has_pgp_keys") val hasPgpKeys: Boolean = false,
)

@Serializable
data class UserProfile(
    val id: String,
    @SerialName("avatar_id") val avatarId: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("has_pgp_keys") val hasPgpKeys: Boolean = false,
    @SerialName("pgp_key_id") val pgpKeyId: String? = null,
    @SerialName("unread_notifications") val unreadNotifications: Int = 0,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class LightningInvoice(
    val bolt11: String = "",
    @SerialName("payment_hash") val paymentHash: String = "",
    @SerialName("amount_sat") val amountSat: Long = 0,
    @SerialName("invoice_amount_sat") val invoiceAmountSat: Long = 0,
    @SerialName("breez_fee_sat") val breezFeeSat: Long = 0,
    @SerialName("breez_send_fee_sat") val breezSendFeeSat: Long = 0,
    val memo: String = "",
)

@Serializable
data class OrderResponse(
    val id: String,
    @SerialName("creator_id") val creatorId: String = "",
    @SerialName("creator_avatar_id") val creatorAvatarId: String = "",
    val type: String = "",
    val amount: Long = 0,
    @SerialName("fiat_amount") val fiatAmount: Double = 0.0,
    val price: Double = 0.0,
    @SerialName("margin_percentage") val marginPercentage: Double = 0.0,
    @SerialName("deposit_amount") val depositAmount: Long = 0,
    val status: String = "",
    @SerialName("payment_method") val paymentMethod: List<String> = emptyList(),
    @SerialName("currency_code") val currencyCode: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("time_remaining") val timeRemaining: Long = 0,
    @SerialName("has_matched_trade") val hasMatchedTrade: Boolean = false,
    @SerialName("creator_online") val creatorOnline: Boolean = false,
    // "bitcoin" | "liquid" (backend exposes it since the Bitcoin settlement branch;
    // defaults to liquid for older orders). Drives which explorer link is built.
    @SerialName("settlement_chain") val settlementChain: String = "liquid",
    @SerialName("funding_tx_id") val fundingTxId: String? = null,
    @SerialName("funding_amount") val fundingAmount: Long = 0,
    @SerialName("value_blinder") val valueBlinder: String? = null,
    @SerialName("asset_blinder") val assetBlinder: String? = null,
)

@Serializable
data class OrdersPage(
    val items: List<OrderResponse> = emptyList(),
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
data class OrderEntity(
    val id: String,
    @SerialName("creator_id") val creatorId: String = "",
    val type: String = "",
    val amount: Long = 0,
    @SerialName("fiat_amount") val fiatAmount: Double = 0.0,
    val price: Double = 0.0,
    @SerialName("margin_percentage") val marginPercentage: Double = 0.0,
    @SerialName("deposit_amount") val depositAmount: Long = 0,
    @SerialName("service_fee") val serviceFee: Long = 0,
    val status: String = "",
    @SerialName("payment_method") val paymentMethod: List<String> = emptyList(),
    @SerialName("currency_code") val currencyCode: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("multisig_address") val multisigAddress: String? = null,
    @SerialName("funding_txid") val fundingTxid: String? = null,
    @SerialName("funding_amount") val fundingAmount: Long = 0,
    @SerialName("refund_txid") val refundTxid: String? = null,
    // Blinding data the backend promotes only for the order creator, used to
    // build unblinded Liquid explorer links (see orderDetailResponse in Go).
    @SerialName("value_blinder") val valueBlinder: String? = null,
    @SerialName("asset_blinder") val assetBlinder: String? = null,
    @SerialName("refund_blinding_data") val refundBlindingData: String? = null,
    @SerialName("settlement_chain") val settlementChain: String = "liquid",
)

@Serializable
data class CreateOrderRequest(
    val type: String,
    @SerialName("fiat_amount") val fiatAmount: Double,
    @SerialName("margin_percentage") val marginPercentage: Double,
    @SerialName("payment_method") val paymentMethod: List<String>,
    @SerialName("currency_code") val currencyCode: String,
    @SerialName("settlement_chain") val settlementChain: String = "liquid",
    val password: String,
)

@Serializable
data class CreateOrderResponse(
    val order: OrderEntity,
    val invoice: LightningInvoice,
)

@Serializable
data class OrderInvoiceResponse(
    val invoice: LightningInvoice,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("trade_id") val tradeId: String? = null,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("deposit_amount") val depositAmount: Long = 0,
    @SerialName("service_fee") val serviceFee: Long = 0,
)

@Serializable
data class PricePreviewResponse(
    @SerialName("order_id") val orderId: String = "",
    @SerialName("amount_sats") val amountSats: Long = 0,
    @SerialName("current_btc_price") val currentBtcPrice: Double = 0.0,
    @SerialName("margin_percentage") val marginPercentage: Double = 0.0,
    @SerialName("final_fiat_amount") val finalFiatAmount: Double = 0.0,
    @SerialName("payment_method") val paymentMethod: List<String> = emptyList(),
    @SerialName("currency_code") val currencyCode: String = "",
    @SerialName("price_updated_at") val priceUpdatedAt: String = "",
)

@Serializable
data class BitcoinPrice(
    val currency: String = "USD",
    val price: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class MatchOrderResponse(
    val invoice: LightningInvoice,
    @SerialName("trade_id") val tradeId: String,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("deposit_amount") val depositAmount: Long = 0,
    @SerialName("service_fee") val serviceFee: Long = 0,
)

@Serializable
data class TradeResponse(
    val id: String,
    @SerialName("order_id") val orderId: String = "",
    @SerialName("buyer_id") val buyerId: String = "",
    @SerialName("seller_id") val sellerId: String = "",
    val amount: Long = 0,
    val status: String = "",
    val price: Double = 0.0,
    @SerialName("final_fiat_amount") val finalFiatAmount: Double = 0.0,
    @SerialName("buyer_deposit") val buyerDeposit: Long = 0,
    @SerialName("buyer_fee") val buyerFee: Long = 0,
    @SerialName("seller_fee") val sellerFee: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("buyer_payment_time_remaining") val buyerPaymentTimeRemaining: Long? = null,
    @SerialName("seller_confirm_time_remaining") val sellerConfirmTimeRemaining: Long? = null,
    @SerialName("currency_code") val currencyCode: String = "",
    @SerialName("buyer_cancel_consent") val buyerCancelConsent: Boolean = false,
    @SerialName("seller_cancel_consent") val sellerCancelConsent: Boolean = false,
    @SerialName("payment_method") val paymentMethod: List<String> = emptyList(),
    @SerialName("buyer_avatar") val buyerAvatar: String = "",
    @SerialName("seller_avatar") val sellerAvatar: String = "",
    @SerialName("buyer_online") val buyerOnline: Boolean = false,
    @SerialName("seller_online") val sellerOnline: Boolean = false,
    @SerialName("buyer_payment_penalty") val buyerPaymentPenalty: Boolean = false,
    @SerialName("seller_confirm_penalty") val sellerConfirmPenalty: Boolean = false,
    @SerialName("multisig_address") val multisigAddress: String? = null,
    @SerialName("release_txid") val releaseTxid: String? = null,
    @SerialName("release_blinding_data") val releaseBlindingData: String? = null,
    @SerialName("dispute_winner_id") val disputeWinnerId: String? = null,
    @SerialName("order_status") val orderStatus: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    // "bitcoin" | "liquid": drives which chain explorer the release TX links to.
    @SerialName("settlement_chain") val settlementChain: String = "liquid",
)

@Serializable
data class TradesPage(
    val items: List<TradeResponse> = emptyList(),
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
data class TimelockStatus(
    @SerialName("trade_id") val tradeId: String = "",
    val status: String = "",
    @SerialName("buyer_payment_time_remaining") val buyerPaymentTimeRemaining: Long = 0,
    @SerialName("seller_confirm_time_remaining") val sellerConfirmTimeRemaining: Long = 0,
)

@Serializable
data class TxidResponse(val txid: String = "")

@Serializable
data class PasswordRequest(val password: String)

@Serializable
data class EncryptedMessage(
    val id: String,
    @SerialName("trade_id") val tradeId: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("sender_avatar") val senderAvatar: String = "",
    @SerialName("encrypted_text") val encryptedText: String = "",
    @SerialName("is_encrypted") val isEncrypted: Boolean = false,
    val signature: String? = null,
    @SerialName("message_type") val messageType: String = "text",
    @SerialName("is_from_self") val isFromSelf: Boolean = false,
    val timestamp: String = "",
)

@Serializable
data class SendMessageRequest(
    @SerialName("encrypted_text") val encryptedText: String? = null,
    val message: String? = null,
    val signature: String? = null,
)

@Serializable
data class CounterpartyPubKey(
    @SerialName("pgp_public_key") val pgpPublicKey: String = "",
)

@Serializable
data class Notification(
    val id: String,
    val type: String = "info",
    val title: String = "",
    val message: String = "",
    val data: JsonObject? = null,
    val read: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class MnemonicResponse(val mnemonic: String)

@Serializable
data class PgpKeysResponse(
    @SerialName("pgp_private_key") val pgpPrivateKey: String = "",
    @SerialName("pgp_public_key") val pgpPublicKey: String = "",
    @SerialName("pgp_key_id") val pgpKeyId: String = "",
)

@Serializable
data class VoucherStock(
    val available: Int = 0,
    @SerialName("duration_months") val durationMonths: Int = 0,
    @SerialName("price_eur") val priceEur: Double = 0.0,
)

@Serializable
data class VoucherPurchase(
    val id: String,
    @SerialName("duration_months") val durationMonths: Int = 0,
    @SerialName("amount_sats") val amountSats: Long = 0,
    @SerialName("amount_fiat") val amountFiat: Double = 0.0,
    val currency: String = "EUR",
    @SerialName("btc_price") val btcPrice: Double = 0.0,
    @SerialName("payment_request") val paymentRequest: String? = null,
    @SerialName("payment_hash") val paymentHash: String? = null,
    val status: String = "pending",
    @SerialName("voucher_code") val voucherCode: String? = null,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
    // Late-payment refund lifecycle (LND/LNbits backends). Empty/null when no
    // refund is pending: "pending_invoice" | "processing" | "completed" | "failed".
    @SerialName("refund_status") val refundStatus: String? = null,
    @SerialName("refund_amount_sats") val refundAmountSats: Long = 0,
    @SerialName("refund_error") val refundError: String? = null,
)

@Serializable
data class VoucherPurchasesPage(
    val items: List<VoucherPurchase> = emptyList(),
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
data class RefundInvoiceRequest(val invoice: String)

@Serializable
data class AdminConversation(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_avatar") val userAvatar: String = "",
    @SerialName("admin_id") val adminId: String? = null,
    @SerialName("admin_avatar") val adminAvatar: String = "",
    val subject: String = "",
    val status: String = "open",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("closed_at") val closedAt: String? = null,
)

@Serializable
data class AdminMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("sender_avatar") val senderAvatar: String = "",
    @SerialName("is_admin") val isAdmin: Boolean = false,
    val content: String = "",
    @SerialName("is_from_self") val isFromSelf: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class AdminReplyRequest(val message: String)

@Serializable
data class Dispute(
    val id: String,
    @SerialName("trade_id") val tradeId: String = "",
    @SerialName("creator_id") val creatorId: String? = null,
    val reason: String = "",
    val status: String = "open",
    val resolution: String = "",
    @SerialName("winner_id") val winnerId: String? = null,
    @SerialName("is_system") val isSystem: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("buyer_id") val buyerId: String = "",
    @SerialName("seller_id") val sellerId: String = "",
    @SerialName("buyer_avatar") val buyerAvatar: String = "",
    @SerialName("seller_avatar") val sellerAvatar: String = "",
    @SerialName("trade_amount") val tradeAmount: Long = 0,
    @SerialName("trade_price") val tradePrice: Double = 0.0,
    @SerialName("currency_code") val currencyCode: String = "EUR",
)

@Serializable
data class DisputesPage(
    val items: List<Dispute> = emptyList(),
    @SerialName("total_count") val totalCount: Int = 0,
)

/**
 * Go's []byte unmarshals JSON strings as base64, so content is base64-encoded.
 * evidenceType must NOT have a default: the app's Json uses encodeDefaults=false,
 * which would silently drop the field from the payload.
 */
@Serializable
data class TextEvidenceRequest(
    @SerialName("evidence_type") val evidenceType: String,
    val content: String,
)

@Serializable
data class ChatEvidenceMessage(
    @SerialName("message_id") val messageId: String,
    @SerialName("decrypted_text") val decryptedText: String,
)

@Serializable
data class ChatEvidenceRequest(val messages: List<ChatEvidenceMessage>)

@Serializable
data class ReputationResponse(
    @SerialName("trust_level") val trustLevel: JsonElement? = null,
    @SerialName("success_rate") val successRate: Double? = null,
    @SerialName("total_trades") val totalTrades: Int? = null,
    @SerialName("successful_trades") val successfulTrades: Int? = null,
    @SerialName("failed_trades") val failedTrades: Int? = null,
)

// --- Settings page (web UserProfilePage parity) ---

@Serializable
data class UserStats(
    @SerialName("avatar_id") val avatarId: String = "",
    @SerialName("trade_success_rate") val tradeSuccessRate: Double = 0.0,
    @SerialName("total_trades") val totalTrades: Int = 0,
    @SerialName("successful_trades") val successfulTrades: Int = 0,
    @SerialName("failed_trades") val failedTrades: Int = 0,
    @SerialName("trust_level") val trustLevel: Int = 0,
    @SerialName("success_rate") val successRate: Double = 0.0,
    @SerialName("completed_trades") val completedTrades: Int = 0,
)

@Serializable
data class TelegramStatus(
    @SerialName("telegram_enabled") val telegramEnabled: Boolean = false,
    @SerialName("notify_new_orders") val notifyNewOrders: Boolean = true,
)

@Serializable
data class TelegramToken(
    val token: String = "",
    @SerialName("bot_username") val botUsername: String = "",
    @SerialName("bot_link") val botLink: String = "",
    val command: String = "",
    @SerialName("expires_in") val expiresIn: String = "1 hour",
)

@Serializable
data class TelegramPreferencesRequest(
    @SerialName("notify_new_orders") val notifyNewOrders: Boolean,
)

/** eligible == false means the user is not (yet) an affiliate. */
@Serializable
data class AffiliateStats(
    val eligible: Boolean? = null,
    val reason: String? = null,
    val message: String? = null,
    @SerialName("referred_users") val referredUsers: Int = 0,
    @SerialName("pending_balance") val pendingBalance: Long = 0,
    @SerialName("min_payout_sats") val minPayoutSats: Long = 0,
    @SerialName("current_code") val currentCode: String = "",
    @SerialName("referral_url") val referralUrl: String? = null,
)

@Serializable
data class BlocklistEntry(
    val id: String,
    @SerialName("blocked_avatar_id") val blockedAvatarId: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class BlockUserRequest(@SerialName("avatar_id") val avatarId: String)
