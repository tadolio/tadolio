// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

object CommunityCrypto {
    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text).also { require(encode(it) == text) }
    fun publicKey(value: String): PublicKey {
        val bytes = Base64.getDecoder().decode(value)
        require(Base64.getEncoder().encodeToString(bytes) == value)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
        val expected = (generate().public as ECPublicKey).params
        require(key.params.curve == expected.curve && key.params.generator == expected.generator && key.params.order == expected.order)
        return key
    }
    fun verifyDescriptor(d: JsonObject, signature: String) {
        val ordered = buildJsonObject {
            for (k in listOf("version", "signingKey", "agreementKey", "kind", "model", "maxConcurrent", "platform", "permission", "configuration")) put(k, d.getValue(k))
        }
        require(d["version"]?.jsonPrimitive?.int == 1)
        publicKey(d.getValue("agreementKey").jsonPrimitive.content)
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(d.getValue("signingKey").jsonPrimitive.content))
            update(ordered.toString().toByteArray(Charsets.UTF_8)); verify(decode(signature))
        }) { "invalid_node_signature" }
    }
    fun keys(secret: PrivateKey, peer: String, session: String): Pair<ByteArray, ByteArray> {
        val shared = KeyAgreement.getInstance("ECDH").run { init(secret); doPhase(publicKey(peer), true); generateSecret() }
        fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(input) }
        val prk = hmac(session.toByteArray(Charsets.UTF_8), shared)
        val info = "hivo-t16-v1".toByteArray(Charsets.UTF_8)
        val first = hmac(prk, info + byteArrayOf(1))
        val second = hmac(prk, first + info + byteArrayOf(2))
        shared.fill(0); prk.fill(0)
        return first to second
    }
    private fun cipher(mode: Int, key: ByteArray, session: String, direction: String, seq: Int): Cipher {
        require(seq > 0 && direction in listOf("c2n", "n2c"))
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(seq.toLong()).array()))
            updateAAD("hivo-t16-v1\n$session\n$direction\n$seq".toByteArray(Charsets.UTF_8))
        }
    }
    fun encrypt(key: ByteArray, session: String, direction: String, seq: Int, plain: String) = encode(cipher(Cipher.ENCRYPT_MODE, key, session, direction, seq).doFinal(plain.toByteArray(Charsets.UTF_8)))
    fun decrypt(key: ByteArray, session: String, direction: String, seq: Int, text: String) = String(cipher(Cipher.DECRYPT_MODE, key, session, direction, seq).doFinal(decode(text)), Charsets.UTF_8)
}
