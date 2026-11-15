// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import kotlinx.serialization.json.*
import com.wangzan101.mika.community.CommunityCrypto
import com.wangzan101.mika.family.FamilyCrypto
import org.junit.Assert.*
import org.junit.Test

class NumberEnvelopeTest {
    private val envelope = NumberEnvelope("propose", "Meeting", "Tomorrow?", "first", "previous", 123456L, auto = true)

    @Test fun oldEnvelopeDefaultsToManualAndBooleanFlagRoundTrips() {
        assertFalse(NumberEnvelope.decode(JsonObject(envelope.encode() - "auto")).auto)
        assertFalse(NumberEnvelope.decode(envelope.copy(auto = false).encode()).auto)
        assertTrue(NumberEnvelope.decode(envelope.encode()).auto)
    }

    @Test fun malformedAutoFlagCannotBeTrusted() {
        for (value in listOf(JsonNull, JsonPrimitive("true"), JsonPrimitive(1), JsonArray(emptyList()))) {
            val decoded = NumberEnvelope.decode(JsonObject(envelope.encode() + ("auto" to value)))
            assertFalse(decoded.auto)
            assertEquals("ask", decoded.kind)
        }
    }

    @Test fun envelopeTravelsInsideExistingEncryptedProtocol() {
        val sender = CommunityCrypto.generate()
        val receiver = CommunityCrypto.generate()
        fun sign(value: String) = CommunityCrypto.encode(java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(sender.private)
            update(value.toByteArray(Charsets.UTF_8))
            sign()
        })
        val key = FamilyCrypto.publicKey(sender.public)
        val body = JsonObject(envelope.encode() + buildJsonObject {
            put("id", "first"); put("from", "12345678"); put("to", "87654321"); put("time", 1000000L)
        })
        val ciphertext = NumberProtocol.seal(sender.private, FamilyCrypto.publicKey(receiver.public), body, ::sign)
        val frame = buildJsonObject {
            put("id", "first"); put("fromNumber", "12345678"); put("toNumber", "87654321")
            put("agreementKey", key); put("signingKey", key)
            put("numberProof", sign(NumberProtocol.binding("12345678", key)))
            put("ciphertext", ciphertext); put("expires", 2000000L)
        }
        val opened = checkNotNull(NumberProtocol.receive(receiver.private, frame, setOf("87654321"), 1000000L))
        assertEquals(envelope, NumberEnvelope.decode(opened))
        assertFalse(ciphertext.contains(envelope.subject))
    }

    @Test fun allKindsRoundTripWithNullableFields() {
        for (kind in NumberEnvelope.kinds) {
            val value = envelope.copy(kind = kind)
            assertEquals(value, NumberEnvelope.decode(value.encode()))
            val nullable = value.copy(replyTo = null, expires = null)
            assertEquals(nullable, NumberEnvelope.decode(nullable.encode()))
        }
    }

    @Test fun legacyBodyKeepsItsFirstMessageIdentity() {
        val body = buildJsonObject { put("id", "old"); put("text", "x".repeat(40)) }
        val value = NumberEnvelope.decode(body)
        assertEquals("ask", value.kind)
        assertEquals("x".repeat(30), value.subject)
        assertEquals("old", value.thread)
        assertEquals("x".repeat(40), value.text)
    }

    @Test fun illegalControlFieldsFallBackWithoutTrustingThreadOrKind() {
        val invalid = mapOf(
            "kind" to JsonPrimitive("execute"),
            "subject" to JsonPrimitive("x".repeat(61)),
            "thread" to JsonPrimitive(""),
            "replyTo" to JsonArray(emptyList()),
            "expires" to JsonPrimitive("123"),
        )
        for ((key, value) in invalid) {
            val body = JsonObject(envelope.encode() + mapOf(key to value, "id" to JsonPrimitive("message")))
            val decoded = NumberEnvelope.decode(body)
            assertEquals("ask", decoded.kind)
            assertEquals("message", decoded.thread)
            assertNull(decoded.replyTo)
            assertNull(decoded.expires)
        }
    }

    @Test fun oversizeLegacyTextIsBoundedAndUnicodeCharactersStayWhole() {
        val face = String(Character.toChars(0x1F600))
        val decoded = NumberEnvelope.decode(buildJsonObject { put("id", "old"); put("text", face.repeat(2001)) })
        assertEquals(face.repeat(30), decoded.subject)
        assertEquals(face.repeat(2000), decoded.text)
        val valid = envelope.copy(subject = face.repeat(60), text = face.repeat(2000))
        assertEquals(valid, NumberEnvelope.decode(valid.encode()))
    }

    @Test fun malformedTextAndFieldsNeverThrow() {
        for (value in listOf(JsonNull, JsonPrimitive(12), JsonArray(emptyList()), buildJsonObject {})) {
            assertEquals("ask", NumberEnvelope.decode(buildJsonObject { put("text", value) }).kind)
            for (field in listOf("kind", "subject", "text", "thread")) {
                assertEquals("ask", NumberEnvelope.decode(JsonObject(envelope.encode() + (field to value))).kind)
            }
        }
    }

    @Test fun oversizedEnvelopeAndNegativeExpiryBecomeOrdinaryAsks() {
        for ((field, value) in listOf(
            "text" to JsonPrimitive("x".repeat(2001)),
            "expires" to JsonPrimitive(-1),
            "thread" to JsonPrimitive("x".repeat(129)),
            "replyTo" to JsonPrimitive("bad\nidentity"),
        )) {
            val result = NumberEnvelope.decode(JsonObject(envelope.encode() + (field to value)))
            assertEquals("ask", result.kind)
            assertTrue(result.text.length <= 2000)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun outgoingInvalidEnvelopeIsRejected() {
        envelope.copy(text = "x".repeat(2001)).encode()
    }
}
