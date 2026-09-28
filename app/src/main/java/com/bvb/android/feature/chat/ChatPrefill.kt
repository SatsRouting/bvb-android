package com.bvb.android.feature.chat

/**
 * One-shot handoff of a prefilled message to the chat screen (e.g. the
 * seller's "Send payment instructions" template, like the web app).
 * Long texts don't survive navigation arguments well, hence this holder.
 */
object ChatPrefill {
    private var tradeId: String? = null
    private var text: String? = null

    fun set(tradeId: String, text: String) {
        this.tradeId = tradeId
        this.text = text
    }

    /** Returns the pending text for [tradeId] (once), or null. */
    fun consume(tradeId: String): String? {
        if (this.tradeId != tradeId) return null
        val pending = text
        this.tradeId = null
        text = null
        return pending
    }
}
