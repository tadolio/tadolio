// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

/** Local encrypted-number mailbox. The app owns persistence and transport. */
interface NumberPort {
    suspend fun bindInviter(number: String) { error("referral_unavailable") }
    val governance: GovernancePort? get() = null
    val coins: com.wangzan101.mika.core.coins.CoinsPort? get() = null
    val posts: com.wangzan101.mika.core.post.PostPort? get() = null
    fun contacted(number: String): Boolean = false
    suspend fun community(number: String): Boolean = false
    fun inbox(): List<Item>
    suspend fun send(number: String, kind: String, subject: String, text: String, thread: String?)
    data class Item(val number: String, val thread: String, val subject: String,
        val summary: String, val state: String, val text: String, val official: String? = null)
}
