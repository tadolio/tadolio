// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import kotlinx.serialization.json.*
import java.security.*
import java.util.Base64

internal object FamilyCrypto {
    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun publicKey(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)
    fun sas(secret: PrivateKey, peer: String, pair: String, transcript: String): String {
        val keys = CommunityCrypto.keys(secret, peer, "family-pair:$pair")
        val digest = MessageDigest.getInstance("SHA-256").digest(keys.first + transcript.toByteArray())
        keys.first.fill(0); keys.second.fill(0)
        val number = java.nio.ByteBuffer.wrap(digest).int.toLong() and 0xffffffffL
        return (number % 1000000).toString().padStart(6, '0')
    }
    fun seal(secret: PrivateKey, peer: String, id: String, body: JsonObject, sign: (String) -> String): String {
        val plain = buildJsonObject { put("body", body); put("signature", sign(body.toString())) }.toString()
        val keys = CommunityCrypto.keys(secret, peer, "family-message:$id")
        return try { CommunityCrypto.encrypt(keys.first, "family-message:$id", "c2n", 1, plain) }
        finally { keys.first.fill(0); keys.second.fill(0) }
    }
    fun open(secret: PrivateKey, peer: String, signingKey: String, id: String, cipher: String): JsonObject {
        val keys = CommunityCrypto.keys(secret, peer, "family-message:$id")
        val envelope = try { Json.parseToJsonElement(CommunityCrypto.decrypt(keys.first, "family-message:$id", "c2n", 1, cipher)).jsonObject }
        finally { keys.first.fill(0); keys.second.fill(0) }
        val body = envelope.getValue("body").jsonObject
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(CommunityCrypto.publicKey(signingKey)); update(body.toString().toByteArray())
            verify(CommunityCrypto.decode(envelope.getValue("signature").jsonPrimitive.content))
        })
        return body
    }
}
