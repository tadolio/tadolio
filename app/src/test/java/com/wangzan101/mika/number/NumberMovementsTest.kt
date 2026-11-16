// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.community.CommunityCrypto
import com.wangzan101.mika.family.FamilyCrypto
import com.wangzan101.mika.family.FamilyPair
import com.wangzan101.mika.family.FamilyPairProofs
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.Signature

class NumberMovementsTest {
    private fun key(pair: KeyPair) = FamilyCrypto.publicKey(pair.public)
    private fun sign(pair: KeyPair, text: String) = CommunityCrypto.encode(Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private);update(text.toByteArray());sign()
    })
    private fun certificate(old: KeyPair, next: KeyPair, agreement: String, recovery: KeyPair? = null) = buildJsonObject {
        put("number","12345678");put("oldKey",key(old));put("newKey",key(next));put("agreement",agreement)
        put("signature",sign(recovery ?: old,NumberMovements.input("12345678",key(old),key(next),agreement)))
        if (recovery != null) {
            put("recoveryKey",key(recovery));put("delegation",sign(old,"hivo-recovery\n12345678\n${key(recovery)}"))
        } else { put("recoveryKey",JsonNull);put("delegation",JsonNull) }
    }
    @Test fun signedTransfersAndRecoveryPreservePinnedTrustAcrossMultiplePhones() {
        val a=CommunityCrypto.generate();val b=CommunityCrypto.generate();val c=CommunityCrypto.generate();val r=CommunityCrypto.generate()
        val chain=JsonArray(listOf(certificate(a,b,"agreement-b"),certificate(b,c,"agreement-c",r)))
        assertTrue(NumberMovements.follows(key(a),key(c),"agreement-c",chain))
        assertTrue(NumberMovements.follows(key(a),key(b),"agreement-b",chain))
        assertFalse(NumberMovements.follows(key(a),key(c),"wrong-agreement",chain))
        assertFalse(NumberMovements.follows(key(c),key(a),null,chain))
    }
    @Test fun stolenShortCodeAndHubKeySubstitutionCannotForgeAContinuityProof() {
        val a=CommunityCrypto.generate();val b=CommunityCrypto.generate();val attacker=CommunityCrypto.generate()
        val cert=certificate(a,b,"agreement-b")
        val forged=JsonObject(cert+("newKey" to JsonPrimitive(key(attacker))))
        assertFalse(NumberMovements.follows(key(a),key(attacker),null,JsonArray(listOf(forged))))
        val recovery=certificate(a,b,"agreement-b",attacker)
        val fake=JsonObject(recovery+("delegation" to JsonPrimitive(sign(attacker,"hivo-recovery\n12345678\n${key(attacker)}"))))
        assertFalse(NumberMovements.follows(key(a),key(b),null,JsonArray(listOf(fake))))
    }
    @Test fun aValidMovementForAnotherNumberCannotChangeAPinnedNumberIdentity() {
        val a=CommunityCrypto.generate();val b=CommunityCrypto.generate()
        val chain=JsonArray(listOf(certificate(a,b,"agreement-b")))
        assertTrue(NumberMovements.follows(key(a),key(b),null,chain,"12345678"))
        assertFalse(NumberMovements.follows(key(a),key(b),null,chain,"87654321"))
    }
    @Test fun familyProofsKeepTheirOriginalSignaturesAndVerifyTheNewRoutingKeys() {
        val a=CommunityCrypto.generate();val b=CommunityCrypto.generate();val next=CommunityCrypto.generate()
        val original=buildJsonObject {
            put("id","pair");put("family",FamilyCrypto.hash(key(a)));put("elder",FamilyCrypto.hash(key(b)))
            put("familyKey",key(a));put("elderKey",key(b));put("agreement_family",key(a));put("agreement_elder",key(b))
            put("confirmed_family",1);put("confirmed_elder",1)
        }
        val transcript=FamilyPair(original).transcript
        val signed=JsonObject(original+buildJsonObject {
            put("familyProof",sign(a,"family-confirm\n$transcript"));put("elderProof",sign(b,"family-confirm\n$transcript"))
        })
        assertTrue(FamilyPairProofs.valid(FamilyPair(signed)))
        val moved=JsonObject(signed+buildJsonObject {
            put("family",FamilyCrypto.hash(key(next)));put("familyKey",key(next));put("agreement_family",key(next))
            put("origin",signed);put("movements",JsonArray(listOf(certificate(a,next,key(next)))))
        })
        assertTrue(FamilyPairProofs.valid(FamilyPair(moved)))
        assertFalse(FamilyPairProofs.valid(FamilyPair(JsonObject(moved+("agreement_elder" to JsonPrimitive(key(next)))))))
        assertFalse(FamilyPairProofs.valid(FamilyPair(JsonObject(moved+("movements" to JsonArray(emptyList()))))))
    }
}
