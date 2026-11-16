// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature

class FamilyProtocolTest {
    private class Transport : FamilyTransport {
        var request: FamilyRequest? = null
        var answer: Boolean? = true
        var offline = false
        var corrupt = false
        override suspend fun send(request: FamilyRequest) { if (offline) error("offline"); this.request = request }
        override suspend fun decisions(): List<FamilyDecision> {
            val r = request ?: return emptyList()
            return answer?.let { listOf(FamilyDecision(r.id, r.pair, if (corrupt) "wrong" else r.digest, it, r.expires)) }.orEmpty()
        }
    }
    @Test fun approvalAndRefusalBindRequestAndDigest() = runTest {
        for (approved in listOf(true, false)) {
            val t = Transport().apply { answer = approved }
            assertEquals(approved, FamilyApproval(t, { 1000 }, {}, 100).approve("pair", "digest", "pay"))
            assertEquals("digest", t.request!!.digest)
            assertEquals("pay", t.request!!.category)
        }
    }
    @Test fun noPairFallsBackWithoutTransport() = runTest {
        val t = Transport().apply { offline = true }
        assertNull(FamilyApproval(t).approve(null, "digest", "pay"))
        assertNull(t.request)
    }
    @Test fun timeoutNetworkFailureAndWrongDigestRefuse() = runTest {
        for (kind in listOf("timeout", "offline", "digest")) {
            val t = Transport().apply { if (kind == "timeout") answer = null; offline = kind == "offline"; corrupt = kind == "digest" }
            var now = 1000L
            assertEquals(false, FamilyApproval(t, { now }, { now += 25 }, 100).approve("pair", "digest", "pay"))
        }
    }
    @Test fun expiredApprovalAndWrongFamilyRefuse() = runTest {
        for (wrongPair in listOf(true, false)) {
            var now = 1000L
            val t = object : FamilyTransport {
                lateinit var r: FamilyRequest
                override suspend fun send(request: FamilyRequest) { r = request }
                override suspend fun decisions(): List<FamilyDecision> {
                    if (!wrongPair) now = r.expires
                    return listOf(FamilyDecision(r.id, if (wrongPair) "another" else r.pair, r.digest, true, r.expires))
                }
            }
            assertEquals(false, FamilyApproval(t, { now }, { now += 25 }, 100).approve("pair", "digest", "pay"))
        }
    }
    @Test fun inPersonCodeMatchesAndSignedCipherRejectsTamperingAndWrongPeer() {
        val a = CommunityCrypto.generate(); val b = CommunityCrypto.generate(); val attacker = CommunityCrypto.generate()
        val ap = FamilyCrypto.publicKey(a.public); val bp = FamilyCrypto.publicKey(b.public)
        val transcript = "pair\n$ap\n$bp"
        assertEquals(FamilyCrypto.sas(a.private, bp, "pair", transcript), FamilyCrypto.sas(b.private, ap, "pair", transcript))
        assertTrue(FamilyCrypto.sas(a.private, bp, "pair", transcript).matches(Regex("[0-9]{6}")))
        val payload = buildJsonObject { put("digest", "bound-operation"); put("request", "id"); put("approved", true) }
        fun sign(text: String) = CommunityCrypto.encode(Signature.getInstance("SHA256withECDSA").run { initSign(a.private); update(text.toByteArray()); sign() })
        val cipher = FamilyCrypto.seal(a.private, bp, "id", payload, ::sign)
        assertEquals(payload, FamilyCrypto.open(b.private, ap, ap, "id", cipher))
        for ((agreement, signing, id, value) in listOf(
            listOf(ap, FamilyCrypto.publicKey(attacker.public), "id", cipher),
            listOf(ap, ap, "other-id", cipher),
            listOf(ap, ap, "id", (if (cipher[0] == 'A') "B" else "A") + cipher.drop(1)),
            listOf(FamilyCrypto.publicKey(attacker.public), ap, "id", cipher))) {
            try { FamilyCrypto.open(b.private, agreement, signing, id, value); fail("must reject") } catch (_: Exception) {}
        }
    }
}
