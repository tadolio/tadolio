// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import kotlinx.serialization.json.*
import java.security.Signature

internal object FamilyRemoval {
    fun input(pair: String) = "family-remove\n$pair"
    fun accepts(pair: FamilyPair, device: String, declaration: JsonObject): Boolean = runCatching {
        require(declaration.str("id") == pair.id)
        val pinned = pair.peerKey(device)
        val signer = declaration["signingKey"]?.jsonPrimitive?.contentOrNull ?: pinned
        if (signer != pinned) require(com.wangzan101.mika.number.NumberMovements.follows(
            pinned,signer,null,declaration.getValue("movements").jsonArray))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(CommunityCrypto.publicKey(signer))
            update(input(pair.id).toByteArray(Charsets.UTF_8))
            verify(CommunityCrypto.decode(declaration.str("proof")))
        }
    }.getOrDefault(false)
    fun retained(pairs: List<FamilyPair>, device: String, declarations: List<JsonObject>) =
        pairs.filterNot { pair -> declarations.any { accepts(pair, device, it) } }
}
