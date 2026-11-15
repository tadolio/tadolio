// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.community.CommunityCrypto
import com.wangzan101.mika.family.str
import kotlinx.serialization.json.*
import java.security.Signature

/** Verify continuity from a locally trusted key, including an owner's signed recovery delegation. */
internal object NumberMovements {
    fun input(number: String, old: String, next: String, agreement: String) = "hivo-move\n$number\n$old\n$next\n$agreement"
    private fun verify(key: String, input: String, proof: String) = Signature.getInstance("SHA256withECDSA").run {
        initVerify(CommunityCrypto.publicKey(key)); update(input.toByteArray()); verify(CommunityCrypto.decode(proof))
    }
    fun follows(old: String, next: String, agreement: String?, movements: JsonArray, number: String? = null): Boolean = runCatching {
        if (old == next) return@runCatching true
        val trusted = mutableSetOf(old)
        for (item in movements) {
            val move = item.jsonObject
            if (number != null && move.str("number") != number) continue
            val current = move.str("oldKey")
            if (current !in trusted) continue
            val recovery = move["recoveryKey"]?.jsonPrimitive?.contentOrNull
            if (recovery != null) require(verify(current, "hivo-recovery\n${move.str("number")}\n$recovery", move.str("delegation")))
            require(verify(recovery ?: current, input(move.str("number"), current, move.str("newKey"), move.str("agreement")), move.str("signature")))
            trusted.add(move.str("newKey"))
            if (move.str("newKey") == next && (agreement == null || move.str("agreement") == agreement)) return@runCatching true
        }
        false
    }.getOrDefault(false)
}
