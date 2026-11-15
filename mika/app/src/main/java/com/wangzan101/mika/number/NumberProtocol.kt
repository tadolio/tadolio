// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.community.CommunityCrypto
import com.wangzan101.mika.family.FamilyCrypto
import com.wangzan101.mika.family.str
import kotlinx.serialization.json.*
import java.security.PrivateKey
import java.security.Signature

internal object NumberProtocol {
    fun binding(number: String, agreement: String) = "hivo-number\n$number\n$agreement"
    fun verifyBinding(number: String, agreement: String, signing: String, proof: String) {
        require(number.matches(Regex("[0-9]{8}")))
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(CommunityCrypto.publicKey(signing))
            update(binding(number, agreement).toByteArray(Charsets.UTF_8))
            verify(CommunityCrypto.decode(proof))
        })
    }
    fun seal(secret: PrivateKey, peer: String, body: JsonObject, sign: (String) -> String) =
        FamilyCrypto.seal(secret, peer, "hivo-number:${body.str("id")}", body, sign)

    fun receive(secret: PrivateKey, frame: JsonObject, ownNumbers: Set<String>, now: Long): JsonObject? = runCatching {
        verifyBinding(frame.str("fromNumber"), frame.str("agreementKey"), frame.str("signingKey"), frame.str("numberProof"))
        val body = FamilyCrypto.open(secret, frame.str("agreementKey"), frame.str("signingKey"), "hivo-number:${frame.str("id")}", frame.str("ciphertext"))
        require(body.str("id") == frame.str("id") && body.str("from") == frame.str("fromNumber"))
        require(body.str("to") == frame.str("toNumber") && body.str("to") in ownNumbers)
        val sent = body.getValue("time").jsonPrimitive.long
        require(sent <= now + 120000 && sent > now - 7 * 86400000L)
        require(frame.getValue("expires").jsonPrimitive.long > now)
        require(body.str("text").isNotBlank())
        // Provenance comes only from authenticated hub routing metadata, never sender plaintext.
        val provider = frame["provider"]?.jsonPrimitive?.contentOrNull
        JsonObject(body - "provider" - "official" + buildJsonObject {
            provider?.let { put("provider", it) }
            frame["official"]?.let { put("official", it) }
        })
    }.getOrNull()
}

/** One committed snapshot, deduplicated before acknowledgement. */
internal class NumberStore(private val read: () -> String?, private val write: (String) -> Unit) {
    @Synchronized fun messages(): List<JsonObject> = Json.parseToJsonElement(read() ?: "[]").jsonArray.map { it.jsonObject }
    @Synchronized fun add(body: JsonObject, outgoing: Boolean): Boolean {
        val old = messages()
        if (old.any { it.str("id") == body.str("id") }) return false
        val envelope = NumberEnvelope.decode(body)
        val number = body.str(if (outgoing) "to" else "from")
        val previous = old.lastOrNull { peer(it) == number && NumberEnvelope.decode(it).thread == envelope.thread }
        val saved = JsonObject(body + buildJsonObject {
            put("outgoing", outgoing)
            put("tier", JsonNull)
            put("takenOver", if (outgoing && body["takenOver"]?.jsonPrimitive?.booleanOrNull == true) true
                else takenOver(number, envelope.thread))
            if (outgoing && envelope.auto && envelope.kind == "reply") {
                for (key in listOf("tier", "summary", "autoNote")) previous?.get(key)?.let { put(key, it) }
                put("state", "pending")
            }
        })
        write(JsonArray(old + saved).toString())
        return true
    }
    /** Old clients stored sender-controlled extra fields. Clear them before enabling hub provenance. */
    @Synchronized fun clearLegacyProviderSources() {
        write(JsonArray(messages().map { JsonObject(it - "provider") }).toString())
    }
    @Synchronized fun clearLegacyOfficialSources() {
        write(JsonArray(messages().map { JsonObject(it - "official") }).toString())
    }
    fun conversations() = messages().groupBy { it.str(if (it.getValue("outgoing").jsonPrimitive.boolean) "to" else "from") }

    /** Scope threads to their peer: another sender cannot reopen or complete a transaction. */
    @Synchronized fun transactions(now: Long = System.currentTimeMillis()): List<NumberTransaction> =
        messages().groupBy { peer(it) to NumberEnvelope.decode(it).thread }.mapNotNull { (key, items) ->
            val substantive = items.filter { NumberEnvelope.decode(it).kind != "ack" }
            val last = substantive.lastOrNull() ?: return@mapNotNull null
            val envelope = NumberEnvelope.decode(last)
            val expired = envelope.expires?.let { it <= now } == true
            val state = when {
                expired -> "processed"
                outgoing(last) && !envelope.auto -> "processed"
                else -> last["state"]?.jsonPrimitive?.contentOrNull ?: "pending"
            }
            val decision = items.lastOrNull { it["autoNote"] != null }
            NumberTransaction(key.first, key.second, envelope.subject, envelope.text,
                last.str("id"), last.getValue("time").jsonPrimitive.long, state,
                last["tier"]?.jsonPrimitive?.contentOrNull ?: "normal",
                last["summary"]?.jsonPrimitive?.contentOrNull ?: envelope.subject,
                last["suggestion"]?.jsonPrimitive?.contentOrNull,
                substantive.count { outgoing(it) && NumberEnvelope.decode(it).auto },
                decision?.get("autoNote")?.jsonPrimitive?.contentOrNull,
                takenOver(key.first, key.second),
                items.lastOrNull { !outgoing(it) }?.get("official")?.jsonPrimitive?.contentOrNull)
        }.sortedByDescending { it.time }

    @Synchronized fun setState(number: String, thread: String, state: String) {
        require(state in setOf("pending", "processed", "filtered"))
        write(JsonArray(messages().map {
            if (peer(it) == number && NumberEnvelope.decode(it).thread == thread)
                JsonObject(it + ("state" to JsonPrimitive(state))) else it
        }).toString())
    }

    fun ownerReplied(number: String) = messages().any {
        // Anyone the owner has written to (not just replied to) is a known contact; automatic acks don't count.
        outgoing(it) && peer(it) == number && NumberEnvelope.decode(it).kind != "ack"
    }

    fun contacted(number: String) = messages().any {
        peer(it) == number && NumberEnvelope.decode(it).kind != "ack"
    }

    fun history(number: String, thread: String) = messages().filter {
        peer(it) == number && NumberEnvelope.decode(it).let { e -> e.thread == thread && e.kind != "ack" }
    }

    fun takenOver(number: String, thread: String) = history(number, thread).lastOrNull {
        it["takenOver"] != null
    }?.get("takenOver")?.jsonPrimitive?.booleanOrNull ?: false

    @Synchronized fun setTakenOver(number: String, thread: String, value: Boolean) {
        write(JsonArray(messages().map {
            if (peer(it) == number && NumberEnvelope.decode(it).thread == thread)
                JsonObject(it + ("takenOver" to JsonPrimitive(value))) else it
        }).toString())
    }

    /** A send failed before anything durable was queued: forget the attempt so the next poll retries it. */
    @Synchronized fun releaseAutoReply(id: String) {
        write(JsonArray(messages().map {
            if (it.str("id") == id) JsonObject(it - "autoClaimed" - "autoNote") else it
        }).toString())
    }

    @Synchronized fun releaseReceipt(number: String, thread: String) {
        write(JsonArray(messages().map {
            if (peer(it) == number && !outgoing(it) && NumberEnvelope.decode(it).thread == thread)
                JsonObject(it - "receiptClaimed") else it
        }).toString())
    }

    /** Claim before the model request. Interrupted work stays pending for the owner. */
    @Synchronized fun claimAutoReply(id: String): Boolean {
        val items = messages()
        val body = items.firstOrNull { it.str("id") == id } ?: return false
        if (outgoing(body) || body["autoClaimed"]?.jsonPrimitive?.booleanOrNull == true) return false
        write(JsonArray(items.map {
            if (it.str("id") == id) JsonObject(it + ("autoClaimed" to JsonPrimitive(true))) else it
        }).toString())
        return true
    }

    @Synchronized fun recordAutoReply(id: String, result: NumberAutoReplyResult) {
        write(JsonArray(messages().map {
            if (it.str("id") == id) JsonObject(it + buildJsonObject {
                put("autoNote", result.note)
                put("state", if (result.action == "close") "processed" else "pending")
            }) else it
        }).toString())
    }

    fun autoRepliesToday(sender: String, now: Long): Int {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return messages().count {
            outgoing(it) && it.str("from") == sender && NumberEnvelope.decode(it).let { e -> e.auto && e.kind == "reply" } &&
                java.time.Instant.ofEpochMilli(it.getValue("time").jsonPrimitive.long).atZone(zone).toLocalDate() == day
        }
    }

    fun lastAutoReplyAt(number: String, thread: String): Long? = history(number, thread).filter {
        outgoing(it) && NumberEnvelope.decode(it).auto
    }.maxOfOrNull { it.getValue("time").jsonPrimitive.long }

    @Synchronized fun recordTriage(id: String, result: NumberTriageResult) {
        write(JsonArray(messages().map {
            if (it.str("id") != id || outgoing(it) || NumberEnvelope.decode(it).kind == "ack") it
            else JsonObject(it + buildJsonObject {
                put("tier", result.tier)
                put("summary", result.summary)
                put("suggestion", result.suggestion?.let(::JsonPrimitive) ?: JsonNull)
                put("state", if (result.tier == "spam") "filtered" else "pending")
            })
        }).toString())
    }

    /** Claim before sending. A crash may omit a receipt but must never create an ack loop. */
    @Synchronized fun claimReceipt(number: String, thread: String, blocked: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        if (blocked) return false
        if (transactions(now).firstOrNull { it.number == number && it.thread == thread }?.state != "pending") return false
        val items = messages()
        val incoming = items.filter {
            peer(it) == number && NumberEnvelope.decode(it).thread == thread &&
                !outgoing(it) && NumberEnvelope.decode(it).kind != "ack"
        }
        if (incoming.isEmpty() || incoming.any { it["receiptClaimed"]?.jsonPrimitive?.booleanOrNull == true })
            return false
        // The owner already wrote in this transaction (e.g. asked first): the other side is answering, not
        // knocking, so "received, will pass it on" would be noise.
        if (items.any { peer(it) == number && outgoing(it) && NumberEnvelope.decode(it).let { e -> e.thread == thread && e.kind != "ack" } })
            return false
        val first = incoming.first()
        val envelope = NumberEnvelope.decode(first)
        if (envelope.expires?.let { it <= now } == true ||
            first["tier"]?.jsonPrimitive?.contentOrNull !in setOf("normal", "urgent")) return false
        write(JsonArray(items.map {
            if (it.str("id") == first.str("id")) JsonObject(it + ("receiptClaimed" to JsonPrimitive(true))) else it
        }).toString())
        return true
    }

    private fun outgoing(body: JsonObject) = body["outgoing"]?.jsonPrimitive?.booleanOrNull == true
    private fun peer(body: JsonObject) = body.str(if (outgoing(body)) "to" else "from")
}

internal data class NumberTransaction(
    val number: String, val thread: String, val subject: String, val text: String,
    val lastId: String, val time: Long, val state: String, val tier: String,
    val summary: String, val suggestion: String?,
    val autoReplies: Int = 0, val autoNote: String? = null, val takenOver: Boolean = false,
    val official: String? = null,
)
