package com.bvb.android.ui.navigation

object Routes {
    const val LOGIN = "login"
    const val CREATE_AVATAR = "create_avatar"
    const val MARKETPLACE = "marketplace"
    // Optional ?avatar= lets the marketplace seed the creator's avatar id so the
    // detail screen never flashes the raw creator UUID while resolving it.
    const val ORDER_DETAIL = "order/{orderId}?avatar={avatar}"
    const val TRADES = "trades"
    const val TRADE_DETAIL = "trade/{tradeId}"
    const val CHAT = "trade/{tradeId}/chat"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val MY_ORDERS = "my_orders"
    const val MESSAGES = "messages"
    const val SERVICES = "services"
    const val DISPUTES = "disputes"
    const val DISPUTE_DETAIL = "dispute/{disputeId}"
    const val RECOVERY = "recovery"
    const val LEARN = "learn"
    const val SUPPORT_CHAT = "support/{conversationId}"

    fun orderDetail(orderId: String, avatar: String? = null) =
        if (avatar.isNullOrEmpty()) "order/$orderId" else "order/$orderId?avatar=$avatar"
    fun disputeDetail(disputeId: String) = "dispute/$disputeId"
    fun tradeDetail(tradeId: String) = "trade/$tradeId"
    fun chat(tradeId: String) = "trade/$tradeId/chat"
    fun supportChat(conversationId: String) = "support/$conversationId"
}
