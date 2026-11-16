// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.coins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64

@Serializable data class CoinSplit(val number: String, val amount: Long, val kind: String)
@Serializable data class CoinMilestone(val amount: Long, val what: String, val due: Long? = null,
    val atDue: String? = null, val milestone: Int = 0, val status: String = "pending")
@Serializable data class CoinTerms(val milestones: List<CoinMilestone>)
@Serializable data class CoinEntry(val id: String, val number: String, val delta: Long, val kind: String,
    val counterparty: String? = null, val post: String? = null, val receipt: String? = null, val at: Long,
    val status: String? = null, val releaseAt: Long? = null, val dispute: String? = null,
    val heldAmount: Long? = null, val title: String? = null, val milestones: List<CoinMilestone> = emptyList())
@Serializable data class CoinBalance(val balance: Long, val entries: List<CoinEntry>)
@Serializable data class CoinPayment(val to: String, val amount: Long, val post: String? = null, val note: String = "", val terms: CoinTerms? = null)
@Serializable data class CoinQuote(val splits: List<CoinSplit>, val quote: String, val title: String? = null)
@Serializable data class CoinReceipt(val id: String, val from: String, val to: String, val amount: Long,
    val post: String?, val splits: List<CoinSplit>, val note: String, val at: Long,
    val hold: Boolean? = null, val status: String? = null, val releaseAt: Long? = null,
    val kind: String? = null, val original: String? = null, val terms: CoinTerms? = null, val milestone: Int? = null)
@Serializable data class SignedCoinReceipt(val receipt: CoinReceipt, val signature: String)

interface CoinsPort {
    fun preview(payment: CoinPayment): CoinQuote
    suspend fun balance(): CoinBalance
    suspend fun pay(payment: CoinPayment, quote: CoinQuote): SignedCoinReceipt
    fun previewReceipt(id: String): SignedCoinReceipt = error("receipt preview unavailable")
    suspend fun release(receipt: String, milestone: Int): SignedCoinReceipt = error("release unavailable")
    suspend fun refund(receipt: String, milestone: Int? = null): SignedCoinReceipt = error("refund unavailable")
    suspend fun accept(receipt: String): SignedCoinReceipt = error("accept unavailable")
    suspend fun decline(receipt: String): SignedCoinReceipt = error("decline unavailable")
    suspend fun cancel(receipt: String): SignedCoinReceipt = error("cancel unavailable")
    suspend fun receipt(id: String): SignedCoinReceipt
}

/** Calls use the existing number device identity. The app invokes these off the UI thread. */
class HubCoinsPort(private val call: (String, JsonObject?) -> JsonElement) : CoinsPort {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private fun body(payment: CoinPayment) = buildJsonObject {
        put("to", payment.to); put("amount", payment.amount); put("note", payment.note)
        payment.post?.let { put("post", it) }
        payment.terms?.let { terms -> put("terms", buildJsonObject {
            put("milestones", JsonArray(terms.milestones.map { m -> buildJsonObject {
                put("amount", m.amount); put("what", m.what)
                m.due?.let { put("due", it) }; m.atDue?.let { put("atDue", it) }
            } }))
        }) }
    }
    override fun preview(payment: CoinPayment): CoinQuote = json.decodeFromJsonElement(call("/v1/coins/quote", body(payment).let {
        JsonObject(it.filterKeys { key -> key != "note" && key != "terms" })
    }))
    override suspend fun balance(): CoinBalance = json.decodeFromJsonElement(call("/v1/coins", null))
    override suspend fun pay(payment: CoinPayment, quote: CoinQuote): SignedCoinReceipt {
        val result = verified(call("/v1/coins/pay", JsonObject(body(payment) + ("quote" to JsonPrimitive(quote.quote)))))
        require(result.receipt.to == payment.to && result.receipt.amount == payment.amount &&
            result.receipt.terms == payment.terms && result.receipt.post == payment.post && result.receipt.note == payment.note && result.receipt.splits == quote.splits)
        return result
    }
    override suspend fun receipt(id: String) = previewReceipt(id)
    override fun previewReceipt(id: String): SignedCoinReceipt {
        require(Regex("[a-f0-9-]{36}").matches(id))
        return verified(call("/v1/coins/receipt/$id", null)).also { require(it.receipt.id == id) }
    }
    private fun settle(id: String, kind: String, milestone: Int? = null): SignedCoinReceipt {
        require(Regex("[a-f0-9-]{36}").matches(id))
        return verified(call("/v1/coins/$kind", buildJsonObject { put("receipt", id); milestone?.let { put("milestone", it) } })).also {
            require(it.receipt.original == id && it.receipt.kind == kind)
        }
    }
    override suspend fun release(receipt: String, milestone: Int) = settle(receipt, "release", milestone)
    override suspend fun refund(receipt: String, milestone: Int?) = settle(receipt, "refund", milestone)
    override suspend fun accept(receipt: String) = settle(receipt, "accept")
    override suspend fun decline(receipt: String) = settle(receipt, "decline")
    override suspend fun cancel(receipt: String) = settle(receipt, "cancel")
    private fun verified(value: JsonElement): SignedCoinReceipt {
        val result = json.decodeFromJsonElement<SignedCoinReceipt>(value)
        val key = call("/v1/coins/key", null).jsonObject.getValue("publicKey").jsonPrimitive.content
        require(CoinSignatures.verify(result, key)) { "invalid_coin_receipt" }
        return result
    }
}

object CoinSignatures {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    fun verify(envelope: SignedCoinReceipt, publicKey: String): Boolean = runCatching {
        run {
            val r = envelope.receipt
            val canonical = buildJsonObject {
                put("id", r.id); put("from", r.from); put("to", r.to); put("amount", r.amount)
                put("post", r.post?.let(::JsonPrimitive) ?: JsonNull)
                put("splits", json.encodeToJsonElement(r.splits)); put("note", r.note); put("at", r.at)
                r.hold?.let { put("hold", it); r.status?.let { put("status", it) }; r.releaseAt?.let { put("releaseAt", it) } }
                r.kind?.let { put("kind", it); r.original?.let { put("original", it) } }
                r.terms?.let { terms -> put("terms", buildJsonObject {
                    put("milestones", JsonArray(terms.milestones.map { m -> buildJsonObject {
                        put("amount", m.amount); put("what", m.what)
                        m.due?.let { put("due", it) }; m.atDue?.let { put("atDue", it) }
                    } }))
                }) }
                if (r.hold == null) r.status?.let { put("status", it) }
                r.milestone?.let { put("milestone", it) }
            }
            Ed25519Verify.verify(Base64.getDecoder().decode(publicKey), canonical.toString().toByteArray(Charsets.UTF_8),
                Base64.getUrlDecoder().decode(envelope.signature))
        }
    }.getOrDefault(false)
}
