// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import kotlinx.serialization.json.*
import java.security.Signature
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object FamilyInvitation {
    fun input(pair: JsonObject) = listOf("family-invite", pair.str("id"), pair.str("familyKey"),
        pair.str("agreement_family"), pair.str("expires")).joinToString("\n")

    private fun valid(key: String, input: String, proof: String): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(CommunityCrypto.publicKey(key))
        update(input.toByteArray())
        verify(CommunityCrypto.decode(proof))
    }

    fun parse(code: String, now: Long): JsonObject {
        require(code.length <= 4096)
        val invitation = Json.parseToJsonElement(String(CommunityCrypto.decode(code.trim()))).jsonObject
        val expires = invitation.getValue("expires").jsonPrimitive.long
        require(expires > now && expires <= now + 86400000)
        val key = invitation.str("familyKey")
        val agreement = invitation.str("agreement")
        CommunityCrypto.publicKey(agreement)
        require(invitation.str("fingerprint") == FamilyCrypto.hash("$key\n$agreement"))
        require(CommunityCrypto.decode(invitation.str("secret")).size == 32)
        val pair = JsonObject(invitation + ("agreement_family" to JsonPrimitive(agreement)))
        require(valid(key, input(pair), invitation.str("proof")))
        return invitation
    }

    fun verifyPair(pair: FamilyPair): Boolean {
        require(valid(pair.data.str("familyKey"), input(pair.data), pair.data.str("inviteProof")))
        require(MessageDigest.isEqual(CommunityCrypto.decode(pair.data.str("joinMac")),
            CommunityCrypto.decode(joinMac(pair.data.str("localInviteSecret"), pair.transcript))))
        return valid(pair.data.str("elderKey"), "family-confirm\n${pair.transcript}", pair.data.str("elderProof"))
    }

    fun joinMac(secret: String, transcript: String): String = CommunityCrypto.encode(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(CommunityCrypto.decode(secret), "HmacSHA256"))
        doFinal("family-join\n$transcript".toByteArray())
    })
}
