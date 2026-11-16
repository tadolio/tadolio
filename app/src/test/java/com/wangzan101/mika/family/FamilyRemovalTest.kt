// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.Signature

class FamilyRemovalTest {
    private val family = CommunityCrypto.generate()
    private val elder = CommunityCrypto.generate()
    private fun key(pair: KeyPair) = FamilyCrypto.publicKey(pair.public)
    private fun device(pair: KeyPair) = FamilyCrypto.hash(key(pair))
    private val pair = FamilyPair(buildJsonObject {
        put("id", "pair-one"); put("elder", device(elder))
        put("familyKey", key(family)); put("elderKey", key(elder))
    })
    private fun declaration(signer: KeyPair, id: String = pair.id, purpose: String = "family-remove") = buildJsonObject {
        put("id", id)
        put("proof", CommunityCrypto.encode(Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private); update("$purpose\n$id".toByteArray()); sign()
        }))
    }
    @Test fun elderRemovalDeletesFamilyCopy() {
        assertTrue(FamilyRemoval.retained(listOf(pair), device(family), listOf(declaration(elder))).isEmpty())
    }
    @Test fun familyRemovalDeletesElderCopy() {
        assertTrue(FamilyRemoval.retained(listOf(pair), device(elder), listOf(declaration(family))).isEmpty())
    }
    @Test fun missingDeclarationAndHubForgeriesRetainLocalTrust() {
        val forged = listOf(
            declaration(CommunityCrypto.generate()), declaration(family),
            declaration(elder, "other-pair"), declaration(elder, purpose = "family-confirm"),
            buildJsonObject { put("id", pair.id); put("proof", "invalid") },
            JsonObject(declaration(elder, "other-pair") + ("id" to JsonPrimitive(pair.id))),
        )
        for (declarations in listOf(emptyList<JsonObject>()) + forged.map { listOf(it) }) {
            assertEquals(listOf(pair), FamilyRemoval.retained(listOf(pair), device(family), declarations))
        }
    }
    @Test fun repeatedDeliveryIsHarmlessAndOnlyRemovesMatchingPair() {
        val other = FamilyPair(JsonObject(pair.data + ("id" to JsonPrimitive("other"))))
        val declarations = listOf(declaration(elder))
        val retained = FamilyRemoval.retained(listOf(pair, other), device(family), declarations)
        assertEquals(listOf(other), retained)
        assertEquals(retained, FamilyRemoval.retained(retained, device(family), declarations))
    }
}
