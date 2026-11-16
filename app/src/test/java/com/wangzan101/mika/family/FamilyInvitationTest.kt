// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.Signature

class FamilyInvitationTest {
    private val family = CommunityCrypto.generate()
    private val agreement = CommunityCrypto.generate()
    private val secret = CommunityCrypto.encode(ByteArray(32) { 42 })
    private val now = 1793851200000L
    private fun sign(key: KeyPair, input: String) = CommunityCrypto.encode(Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private); update(input.toByteArray()); sign()
    })
    private fun invitation(): JsonObject {
        val key = FamilyCrypto.publicKey(family.public)
        val exchange = FamilyCrypto.publicKey(agreement.public)
        val pair = buildJsonObject {
            put("id", "invitation-id"); put("familyKey", key); put("agreement_family", exchange); put("expires", now + 86400000)
        }
        return JsonObject(pair + mapOf(
            "secret" to JsonPrimitive(secret), "agreement" to JsonPrimitive(exchange), "proof" to JsonPrimitive(sign(family, FamilyInvitation.input(pair))),
            "fingerprint" to JsonPrimitive(FamilyCrypto.hash("$key\n$exchange"))))
    }
    private fun encode(value: JsonObject) = CommunityCrypto.encode(value.toString().toByteArray())
    private fun rejects(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }
    @Test fun acceptsSignedInvitationAndRejectsExactExpiry() {
        val value = invitation()
        assertEquals(value, FamilyInvitation.parse(encode(value), now))
        FamilyInvitation.parse(encode(value), now + 86399999)
        rejects { FamilyInvitation.parse(encode(value), now + 86400000) }
    }
    @Test fun nicknameTravelsInsideTheInvitation() {
        val value = JsonObject(invitation() + ("name" to JsonPrimitive("闺女")))
        val code = FamilyShortCode.create()
        val encrypted = FamilyShortCode.seal(code, value.toString())
        val message = FamilyInviteEntry.share(code, "闺女")
        val receivedCode = checkNotNull(FamilyInviteEntry.extract(message))
        val received = FamilyInvitation.parse(CommunityCrypto.encode(FamilyShortCode.open(receivedCode, encrypted).toByteArray()), now)
        assertEquals("闺女", received.str("name"))
    }
    @Test fun rejectsHubKeyReplacementAndForgedExpiry() {
        for (field in listOf("familyKey", "agreement", "fingerprint", "proof", "id", "expires")) {
            val replacement = if (field == "expires") JsonPrimitive(now + 10000) else JsonPrimitive(FamilyCrypto.publicKey(CommunityCrypto.generate().public))
            rejects { FamilyInvitation.parse(encode(JsonObject(invitation() + (field to replacement))), now) }
        }
    }
    @Test fun pairProofBindsBothKeysAndRecipientWithoutManualConfirmation() {
        val invitation = invitation()
        val elder = CommunityCrypto.generate()
        val pair = FamilyPair(JsonObject(invitation + mapOf(
            "elderKey" to JsonPrimitive(FamilyCrypto.publicKey(elder.public)),
            "agreement_elder" to JsonPrimitive(FamilyCrypto.publicKey(elder.public)),
            "localInviteSecret" to JsonPrimitive(secret), "inviteProof" to invitation.getValue("proof"))))
        val signed = FamilyPair(JsonObject(pair.data + mapOf("elderProof" to JsonPrimitive(sign(elder, "family-confirm\n${pair.transcript}")),
            "joinMac" to JsonPrimitive(FamilyInvitation.joinMac(secret, pair.transcript)))))
        assertTrue(FamilyInvitation.verifyPair(signed))
        rejects { FamilyInvitation.verifyPair(FamilyPair(JsonObject(signed.data + ("localInviteSecret" to JsonPrimitive(CommunityCrypto.encode(ByteArray(32) { 1 })))))) }
        assertFalse(runCatching { FamilyInvitation.verifyPair(FamilyPair(JsonObject(signed.data + ("elderKey" to JsonPrimitive(FamilyCrypto.publicKey(family.public)))))) }.getOrDefault(false))
    }
}
