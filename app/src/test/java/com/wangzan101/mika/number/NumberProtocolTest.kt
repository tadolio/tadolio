// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.community.CommunityCrypto
import com.wangzan101.mika.family.FamilyCrypto
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature

class NumberProtocolTest {
    private fun sign(secret: java.security.PrivateKey, value: String) = CommunityCrypto.encode(Signature.getInstance("SHA256withECDSA").run {
        initSign(secret); update(value.toByteArray()); sign()
    })
    private fun body(id: String = "message", from: String = "12345678", to: String = "87654321") = buildJsonObject {
        put("id", id); put("from", from); put("to", to); put("time", 1000000L); put("text", "private message")
    }
    @Test fun encryptedRoundTripBindsSenderRecipientNumberAndTime() {
        val a = CommunityCrypto.generate(); val b = CommunityCrypto.generate()
        val ap = FamilyCrypto.publicKey(a.public); val bp = FamilyCrypto.publicKey(b.public)
        val plain = body()
        val cipher = NumberProtocol.seal(a.private, bp, plain) { sign(a.private, it) }
        assertFalse(cipher.contains("private message"))
        val frame = buildJsonObject {
            put("id", "message"); put("fromNumber", "12345678"); put("toNumber", "87654321")
            put("agreementKey", ap); put("signingKey", ap)
            put("numberProof", sign(a.private, NumberProtocol.binding("12345678", ap)))
            put("ciphertext", cipher); put("expires", 2000000L)
        }
        assertEquals(plain, NumberProtocol.receive(b.private, frame, setOf("87654321"), 1000000L))
        val officialFrame = JsonObject(frame + ("official" to JsonPrimitive("Tadolio Official")))
        assertEquals("Tadolio Official", NumberProtocol.receive(b.private, officialFrame, setOf("87654321"), 1000000L)!!["official"]!!.jsonPrimitive.content)
        val fakeOfficial = JsonObject(plain + ("official" to JsonPrimitive("Official")))
        val fakeFrame = JsonObject(frame + ("ciphertext" to JsonPrimitive(
            NumberProtocol.seal(a.private, bp, fakeOfficial) { sign(a.private, it) })))
        assertFalse(NumberProtocol.receive(b.private, fakeFrame, setOf("87654321"), 1000000L)!!.containsKey("official"))
        val nullFrame = JsonObject(fakeFrame + ("official" to JsonNull))
        assertEquals(JsonNull, NumberProtocol.receive(b.private, nullFrame, setOf("87654321"), 1000000L)!!["official"])
        val providerFrame = JsonObject(frame + ("provider" to JsonPrimitive("Test Provider")))
        val received = NumberProtocol.receive(b.private, providerFrame, setOf("87654321"), 1000000L)!!
        assertEquals("Test Provider", received["provider"]!!.jsonPrimitive.content)
        val forged = JsonObject(plain + ("provider" to JsonPrimitive("Forged Provider")))
        val forgedFrame = JsonObject(frame + ("ciphertext" to JsonPrimitive(
            NumberProtocol.seal(a.private, bp, forged) { sign(a.private, it) })))
        assertFalse(NumberProtocol.receive(b.private, forgedFrame, setOf("87654321"), 1000000L)!!.containsKey("provider"))

        assertNull(NumberProtocol.receive(b.private, frame, setOf("11111111"), 1000000L))
        assertNull(NumberProtocol.receive(b.private, frame, setOf("87654321"), 2000000L))
        assertNull(NumberProtocol.receive(b.private, JsonObject(frame + ("fromNumber" to JsonPrimitive("11111111"))), setOf("87654321"), 1000000L))
    }
    @Test fun invalidSenderSignatureIsDiscardedEvenWithValidEncryptionAndNumberProof() {
        val a = CommunityCrypto.generate(); val b = CommunityCrypto.generate(); val attacker = CommunityCrypto.generate()
        val ap = FamilyCrypto.publicKey(a.public)
        val forged = NumberProtocol.seal(a.private, FamilyCrypto.publicKey(b.public), body()) { sign(attacker.private, it) }
        val frame = buildJsonObject {
            put("id", "message"); put("fromNumber", "12345678"); put("toNumber", "87654321")
            put("agreementKey", ap); put("signingKey", ap)
            put("numberProof", sign(a.private, NumberProtocol.binding("12345678", ap)))
            put("ciphertext", forged); put("expires", 2000000L)
        }
        assertNull(NumberProtocol.receive(b.private, frame, setOf("87654321"), 1000000L))
        var stored: String? = null
        val store = NumberStore({ stored }, { stored = it })
        NumberProtocol.receive(b.private, frame, setOf("87654321"), 1000000L)?.let { store.add(it, false) }
        assertTrue(store.messages().isEmpty())
    }
    @Test fun legacySenderFieldsCannotAcquireTrustedProviderProvenanceAfterUpgrade() {
        var data: String? = null
        val store = NumberStore({ data }, { data = it })
        store.add(JsonObject(body() + ("provider" to JsonPrimitive("Forged Provider")) + ("official" to JsonPrimitive("Official"))), false)
        val original = store.messages().single()
        store.clearLegacyOfficialSources()
        assertFalse(store.messages().single().containsKey("official"))
        store.clearLegacyProviderSources()
        assertEquals(JsonObject(original - "provider" - "official"), store.messages().single())
        store.clearLegacyProviderSources()
        assertFalse(store.messages().single().containsKey("provider"))
    }
    @Test fun persistentConversationsGroupBothDirectionsByPeerAndTierDefaultsToNull() {
        var data: String? = null
        val store = NumberStore({ data }, { data = it })
        assertTrue(store.add(body("one", "12345678", "87654321"), false))
        assertTrue(store.add(body("two", "87654321", "12345678"), true))
        assertTrue(store.add(body("three", "11111111", "87654321"), false))
        assertFalse(store.add(body("one"), false))
        val reopened = NumberStore({ data }, { data = it })
        assertEquals(2, reopened.conversations().getValue("12345678").size)
        assertEquals(1, reopened.conversations().getValue("11111111").size)
        assertTrue(reopened.messages().all { it.getValue("tier") == JsonNull })
    }
}
