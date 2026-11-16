// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.coins

import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fallback runs on phones whose system provider has no Ed25519 (seen on OPPO). */
class Ed25519VerifyTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun rfc8032Vector() {
        val key = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(Ed25519Verify.verifyRaw(key, ByteArray(0), sig))
        assertFalse(Ed25519Verify.verifyRaw(key, byteArrayOf(0), sig))
    }

    @Test fun matchesSystemProvider() {
        val random = Random(38)
        repeat(20) {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val raw = pair.public.encoded.copyOfRange(12, 44)
            val message = random.nextBytes(random.nextInt(0, 300))
            val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(message); sign() }
            assertTrue(Ed25519Verify.verifyRaw(raw, message, sig))
            val i = random.nextInt(64)
            val badSig = sig.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse(Ed25519Verify.verifyRaw(raw, message, badSig))
            assertFalse(Ed25519Verify.verifyRaw(raw, message + 1, sig))
        }
    }

    @Test fun rejectsNonCanonicalScalar() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw = pair.public.encoded.copyOfRange(12, 44)
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(byteArrayOf(1)); sign() }
        // s + L encodes the same scalar but must be refused.
        val l = java.math.BigInteger.ONE.shiftLeft(252) + java.math.BigInteger("27742317777372353535851937790883648493")
        val s = java.math.BigInteger(1, sig.copyOfRange(32, 64).reversedArray()) + l
        val sBytes = s.toByteArray().reversedArray().copyOf(32)
        assertFalse(Ed25519Verify.verifyRaw(raw, byteArrayOf(1), sig.copyOfRange(0, 32) + sBytes))
    }

    @Test fun liveHubReceipt() {
        val raw = """{"receipt":{"id":"502a5e48-e244-4a84-8175-5ba33be320eb","from":"64280197","to":"39589463","amount":5,"post":null,"splits":[{"number":"39589463","amount":5,"kind":"receive"}],"note":"Cross-border purchase: 2 coins on shipment, 3 coins on receipt. If not received by December 20, refund the remaining 3 coins to the buyer.","at":1794319343062,"terms":{"milestones":[{"amount":2,"what":"Seller ships the cross-border purchase; release 2 coins once shipment is confirmed"},{"amount":3,"what":"Buyer receives the cross-border purchase; release 3 coins once receipt is confirmed","due":1797811199000,"atDue":"refund"}]},"status":"proposed"},"signature":"EjRthdPTQIu0PnSOjj9K14rBAI2awB9xNDHq30oQ-vuL6HPso6bgVi79GNziuHAZYA9UjtP4aCFOKAKqse7NBg"}"""
        val key = "MCowBQYDK2VwAyEAyIbwb14fCT3hgeoI8py5Hs9O4i/whuzU7ZRY8sOg7Xc="
        val receiptJson = raw.substringAfter("\"receipt\":").substringBefore(",\"signature\"")
        val sig = java.util.Base64.getUrlDecoder().decode(raw.substringAfter("\"signature\":\"").substringBefore("\""))
        val spki = java.util.Base64.getDecoder().decode(key)
        assertTrue(Ed25519Verify.verifyRaw(spki.copyOfRange(12, 44), receiptJson.toByteArray(), sig))
        val envelope = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(SignedCoinReceipt.serializer(), raw)
        assertTrue(CoinSignatures.verify(envelope, key))
        assertFalse(CoinSignatures.verify(envelope.copy(receipt = envelope.receipt.copy(amount = 6)), key))
    }
}
