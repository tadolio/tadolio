// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import kotlinx.serialization.json.*

/** Transaction fields live inside the signed, encrypted message body. */
internal data class NumberEnvelope(
    val kind: String,
    val subject: String,
    val text: String,
    val thread: String,
    val replyTo: String? = null,
    val expires: Long? = null,
    val auto: Boolean = false,
) {
    fun encode(): JsonObject {
        val fields = buildJsonObject {
            put("kind", kind)
            put("subject", subject)
            put("text", text)
            put("thread", thread)
            put("replyTo", replyTo?.let(::JsonPrimitive) ?: JsonNull)
            put("expires", expires?.let(::JsonPrimitive) ?: JsonNull)
            put("auto", auto)
        }
        require(parse(fields) == this) { "invalid_number_envelope" }
        return fields
    }

    companion object {
        val kinds = setOf("ask", "inform", "propose", "reply", "ack")

        /** Invalid envelopes lose control fields and become bounded, ordinary asks. */
        fun decode(body: JsonObject): NumberEnvelope = parse(body) ?: legacy(
            string(body, "text").orEmpty(), string(body, "id").orEmpty(),
        )

        fun legacy(text: String, id: String): NumberEnvelope = NumberEnvelope(
            "ask", text.takeCharacters(30), text.takeCharacters(2000), id,
        )

        private fun string(body: JsonObject, key: String): String? =
            (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun identifier(value: String?) =
            value != null && value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() }

        private fun parse(body: JsonObject): NumberEnvelope? {
            val kind = string(body, "kind") ?: return null
            val subject = string(body, "subject") ?: return null
            val text = string(body, "text") ?: return null
            val thread = string(body, "thread") ?: return null
            val replyTo = string(body, "replyTo")
            val expiry = body["expires"]
            val expires = (expiry as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            val automatic = body["auto"]
            val auto = (automatic as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (automatic != null && auto == null) return null
            if (kind !in kinds || subject.isBlank() || subject.characterCount() > 60 ||
                text.isBlank() || text.characterCount() > 2000 || !identifier(thread)) return null
            if (body["replyTo"] != null && body["replyTo"] != JsonNull && !identifier(replyTo)) return null
            if (expiry != null && expiry != JsonNull && (expires == null || expires < 0)) return null
            return NumberEnvelope(kind, subject, text, thread, replyTo, expires, auto ?: false)
        }

        private fun String.characterCount() = codePointCount(0, length)
        private fun String.takeCharacters(count: Int) = substring(0, offsetByCodePoints(0, minOf(count, characterCount())))
    }
}
