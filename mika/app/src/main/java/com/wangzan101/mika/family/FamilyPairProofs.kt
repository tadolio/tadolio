// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import kotlinx.serialization.json.*
import java.security.Signature

internal object FamilyPairProofs {
    fun valid(pair: FamilyPair): Boolean = runCatching {
        for (side in listOf("family","elder")) {
            if (pair.data[side] != JsonNull) require(FamilyCrypto.hash(pair.data.str("${side}Key")) == pair.data.str(side))
        }
        val origin = pair.data["origin"] as? JsonObject
        if (origin != null) {
            require(origin["id"] == pair.data["id"])
            val original = FamilyPair(JsonObject(origin + pair.data.filterKeys { it == "localInviteSecret" }))
            require(valid(original))
            val movements = pair.data.getValue("movements").jsonArray
            for (side in listOf("family","elder")) {
                if (origin["${side}Key"] == JsonNull) continue
                if (origin.str("${side}Key") == pair.data.str("${side}Key")) require(origin["agreement_$side"] == pair.data["agreement_$side"])
                else require(com.wangzan101.mika.number.NumberMovements.follows(origin.str("${side}Key"),pair.data.str("${side}Key"),pair.data.str("agreement_$side"),movements))
            }
            return@runCatching true
        }
        if (pair.data["inviteProof"] != null && pair.data["inviteProof"] != JsonNull) {
            return@runCatching FamilyInvitation.verifyPair(pair)
        }
        listOf("family", "elder").all { who -> Signature.getInstance("SHA256withECDSA").run {
            initVerify(CommunityCrypto.publicKey(pair.data.str("${who}Key")))
            update("family-confirm\n${pair.transcript}".toByteArray())
            verify(CommunityCrypto.decode(pair.data.str("${who}Proof")))
        } }
    }.getOrDefault(false)
}
