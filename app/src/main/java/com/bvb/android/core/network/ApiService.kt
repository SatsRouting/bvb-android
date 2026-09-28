package com.bvb.android.core.network

import com.bvb.android.data.model.AdminConversation
import com.bvb.android.data.model.AdminMessage
import com.bvb.android.data.model.AdminReplyRequest
import com.bvb.android.data.model.AffiliateStats
import com.bvb.android.data.model.BitcoinPrice
import com.bvb.android.data.model.BlockUserRequest
import com.bvb.android.data.model.BlocklistEntry
import com.bvb.android.data.model.ChatEvidenceRequest
import com.bvb.android.data.model.CounterpartyPubKey
import com.bvb.android.data.model.Dispute
import com.bvb.android.data.model.DisputesPage
import com.bvb.android.data.model.CreateAvatarRequest
import com.bvb.android.data.model.CreateAvatarResponse
import com.bvb.android.data.model.CreateOrderRequest
import com.bvb.android.data.model.CreateOrderResponse
import com.bvb.android.data.model.EncryptedMessage
import com.bvb.android.data.model.LightningInvoice
import com.bvb.android.data.model.LoginRequest
import com.bvb.android.data.model.LoginResponse
import com.bvb.android.data.model.MatchOrderResponse
import com.bvb.android.data.model.MnemonicResponse
import com.bvb.android.data.model.Notification
import com.bvb.android.data.model.OrderEntity
import com.bvb.android.data.model.OrderInvoiceResponse
import com.bvb.android.data.model.OrdersPage
import com.bvb.android.data.model.PasswordRequest
import com.bvb.android.data.model.PgpKeysResponse
import com.bvb.android.data.model.PricePreviewResponse
import com.bvb.android.data.model.RefundInvoiceRequest
import com.bvb.android.data.model.SendMessageRequest
import com.bvb.android.data.model.TelegramPreferencesRequest
import com.bvb.android.data.model.TelegramStatus
import com.bvb.android.data.model.TelegramToken
import com.bvb.android.data.model.TextEvidenceRequest
import com.bvb.android.data.model.TimelockStatus
import com.bvb.android.data.model.TradeResponse
import com.bvb.android.data.model.TradesPage
import com.bvb.android.data.model.TxidResponse
import com.bvb.android.data.model.UserProfile
import com.bvb.android.data.model.UserStats
import com.bvb.android.data.model.VoucherPurchase
import com.bvb.android.data.model.VoucherPurchasesPage
import com.bvb.android.data.model.VoucherStock
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {

    // --- Auth ---
    @POST("api/login")
    suspend fun login(
        @Body body: LoginRequest,
        @Header("X-Turnstile-Token") turnstileToken: String? = null,
    ): LoginResponse

    @POST("api/logout")
    suspend fun logout()

    @POST("api/avatar/create")
    suspend fun createAvatar(
        @Body body: CreateAvatarRequest,
        @Header("X-Turnstile-Token") turnstileToken: String? = null,
    ): CreateAvatarResponse

    // --- User ---
    @GET("api/user/profile")
    suspend fun getProfile(): UserProfile

    @POST("api/user/mnemonic")
    suspend fun revealMnemonic(@Body body: PasswordRequest): MnemonicResponse

    @POST("api/user/pgp-private-key")
    suspend fun revealPgpKeys(@Body body: PasswordRequest): PgpKeysResponse

    @GET("api/users/{id}/reputation")
    suspend fun getUserReputation(@Path("id") userId: String): JsonObject

    // --- Settings (web UserProfilePage parity) ---
    @GET("api/users/{id}/stats")
    suspend fun getUserStats(@Path("id") userId: String): UserStats

    @GET("api/user/telegram/status")
    suspend fun getTelegramStatus(): TelegramStatus

    @POST("api/user/telegram/token")
    suspend fun generateTelegramToken(): TelegramToken

    @POST("api/user/telegram/disable")
    suspend fun disableTelegram()

    @PUT("api/user/telegram/preferences")
    suspend fun updateTelegramPreferences(@Body body: TelegramPreferencesRequest)

    @GET("api/user/affiliate/stats")
    suspend fun getAffiliateStats(): AffiliateStats

    @POST("api/user/affiliate/code")
    suspend fun generateReferralCode()

    // Go encodes empty slices as JSON null (see NullJsonInterceptor).
    @GET("api/user/blocklist")
    suspend fun getBlocklist(): List<BlocklistEntry>?

    @POST("api/user/blocklist")
    suspend fun blockUser(@Body body: BlockUserRequest)

    @DELETE("api/user/blocklist/{avatarId}")
    suspend fun unblockUser(@Path("avatarId") avatarId: String)

    // --- Market ---
    @GET("api/bitcoin/price")
    suspend fun getBitcoinPrice(@Query("currency") currency: String = "USD"): BitcoinPrice

    @GET("api/orders")
    suspend fun listOrders(
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("type") type: String? = null,
        @Query("currency") currency: String? = null,
        @Query("payment_method") paymentMethod: String? = null,
    ): OrdersPage

    @GET("api/orders/{id}")
    suspend fun getOrder(@Path("id") orderId: String): OrderEntity

    @POST("api/orders")
    suspend fun createOrder(@Body body: CreateOrderRequest): CreateOrderResponse

    @POST("api/orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") orderId: String, @Body body: PasswordRequest)

    @GET("api/orders/{id}/invoice")
    suspend fun getOrderInvoice(@Path("id") orderId: String): OrderInvoiceResponse

    @GET("api/orders/{id}/price-preview")
    suspend fun getOrderPricePreview(
        @Path("id") orderId: String,
        @Query("currency") currency: String? = null,
    ): PricePreviewResponse

    // --- Escrow ---
    @POST("api/escrow/order-deposit/{orderID}")
    suspend fun orderDeposit(@Path("orderID") orderId: String, @Body body: PasswordRequest): LightningInvoice

    @POST("api/escrow/match-order/{orderID}")
    suspend fun matchOrder(@Path("orderID") orderId: String): MatchOrderResponse

    @POST("api/escrow/buyer-payment-sent/{tradeID}")
    suspend fun buyerPaymentSent(@Path("tradeID") tradeId: String)

    @POST("api/escrow/seller-confirm-payment/{tradeID}")
    suspend fun sellerConfirmPayment(@Path("tradeID") tradeId: String, @Body body: PasswordRequest): TxidResponse

    @POST("api/escrow/claim-buyer-payment-timeout/{tradeID}")
    suspend fun claimBuyerPaymentTimeout(@Path("tradeID") tradeId: String, @Body body: PasswordRequest): TxidResponse

    @POST("api/escrow/claim-dispute/{tradeID}")
    suspend fun claimDispute(@Path("tradeID") tradeId: String, @Body body: PasswordRequest): TxidResponse

    // --- Trades ---
    @GET("api/trades")
    suspend fun listTrades(
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("include_order_status") includeOrderStatus: Boolean = true,
        @Query("status") status: String? = null,
    ): TradesPage

    @GET("api/trades/{id}")
    suspend fun getTrade(@Path("id") tradeId: String): TradeResponse

    @GET("api/trades/{id}/timelock-status")
    suspend fun getTimelockStatus(@Path("id") tradeId: String): TimelockStatus

    @GET("api/trades/{tradeID}/invoice")
    suspend fun getTradeInvoice(@Path("tradeID") tradeId: String): OrderInvoiceResponse

    @POST("api/trades/{id}/cancel-request")
    suspend fun requestCancellation(@Path("id") tradeId: String, @Body body: PasswordRequest)

    @POST("api/trades/{id}/cancel-confirm")
    suspend fun confirmCancellation(@Path("id") tradeId: String, @Body body: PasswordRequest): TxidResponse

    @POST("api/trades/{id}/cancel-reject")
    suspend fun rejectCancellation(@Path("id") tradeId: String)

    // --- Messages ---
    @GET("api/trades/{id}/messages")
    // Go encodes empty slices as JSON null, hence the nullable list types below.
    suspend fun getMessages(@Path("id") tradeId: String): List<EncryptedMessage>?

    @POST("api/trades/{id}/messages")
    suspend fun sendMessage(@Path("id") tradeId: String, @Body body: SendMessageRequest)

    @GET("api/trades/{id}/counterparty-pubkey")
    suspend fun getCounterpartyPubKey(@Path("id") tradeId: String): CounterpartyPubKey

    @Streaming
    @GET("api/messages/{id}/attachment")
    suspend fun getMessageAttachment(@Path("id") messageId: String): ResponseBody

    @Multipart
    @POST("api/trades/{id}/messages/image")
    suspend fun sendImageMessage(
        @Path("id") tradeId: String,
        @Part encryptedImage: MultipartBody.Part,
        @Part("signature") signature: RequestBody? = null,
        @Part("content_type") contentType: RequestBody? = null,
    )

    // --- Notifications ---
    @GET("api/notifications")
    suspend fun getNotifications(): List<Notification>?

    @POST("api/notifications/{id}/mark-read")
    suspend fun markNotificationRead(@Path("id") notificationId: String)

    // --- My orders / recovery ---
    @GET("api/user/orders")
    suspend fun listUserOrders(
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("status") status: String? = null,
    ): OrdersPage

    @GET("api/user/orders/corrupted")
    suspend fun listCorruptedOrders(): OrdersPage

    // --- Vouchers (Services) ---
    @GET("api/vouchers/stock")
    suspend fun getVoucherStock(): VoucherStock

    @POST("api/vouchers/purchase")
    suspend fun purchaseVoucher(): VoucherPurchase

    @GET("api/vouchers/purchases/{id}")
    suspend fun getVoucherPurchase(@Path("id") purchaseId: String): VoucherPurchase

    // Purchase history, used to surface late-payment refunds needing an invoice.
    @GET("api/vouchers/purchases")
    suspend fun getVoucherPurchases(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): VoucherPurchasesPage

    // Pay a user-supplied BOLT11 to refund a late voucher payment (LND/LNbits).
    @POST("api/vouchers/purchases/{id}/refund-invoice")
    suspend fun submitVoucherRefundInvoice(
        @Path("id") purchaseId: String,
        @Body body: RefundInvoiceRequest,
    )

    // --- Learn (static docs served by the frontend web server) ---
    @Streaming
    @GET("docs/{slug}.md")
    suspend fun getLearnDoc(@Path("slug") slug: String): ResponseBody

    // --- Conversations (Messages page) ---
    @GET("api/trades/conversations")
    suspend fun listConversations(
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): TradesPage

    @GET("api/admin-conversations")
    suspend fun listAdminConversations(): List<AdminConversation>?

    @GET("api/admin-conversations/{id}/messages")
    suspend fun getAdminConversationMessages(@Path("id") conversationId: String): List<AdminMessage>?

    @POST("api/admin-conversations/{id}/reply")
    suspend fun replyAdminConversation(@Path("id") conversationId: String, @Body body: AdminReplyRequest)

    @POST("api/admin-conversations/open-ticket")
    suspend fun openSupportTicket(@Body body: AdminReplyRequest): AdminConversation

    // --- Disputes ---
    @GET("api/disputes")
    suspend fun listDisputes(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): DisputesPage

    @GET("api/disputes/{id}")
    suspend fun getDispute(@Path("id") disputeId: String): Dispute

    @POST("api/disputes/{id}/evidence")
    suspend fun addTextEvidence(@Path("id") disputeId: String, @Body body: TextEvidenceRequest)

    @Multipart
    @POST("api/disputes/{id}/evidence")
    suspend fun addFileEvidence(@Path("id") disputeId: String, @Part file: MultipartBody.Part)

    @POST("api/disputes/{id}/chat-evidence")
    suspend fun submitChatEvidence(@Path("id") disputeId: String, @Body body: ChatEvidenceRequest)
}
