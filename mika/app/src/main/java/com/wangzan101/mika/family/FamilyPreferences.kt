// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.core.action.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Local persistence boundary. [save] must commit the supplied keys in one transaction. */
internal class FamilyPreferences(
    private val read: (String) -> String?,
    private val save: (Map<String, String>) -> Unit,
    private val recipients: () -> List<String>,
) {
    @Synchronized fun policy() = FamilyPolicy(FamilyCategory.entries.associateWith { category ->
        runCatching { FamilyMode.valueOf(read("policy_${category.wire}") ?: "Private") }.getOrDefault(FamilyMode.Private)
    })
    @Synchronized fun pending(): List<JsonObject> = Json.parseToJsonElement(read("notices") ?: "[]").jsonArray.map { it.jsonObject }
    private fun notice(pair: String, category: String) = buildJsonObject {
        put("id", UUID.randomUUID().toString()); put("pair", pair); put("category", category)
    }
    @Synchronized fun joined(pair: FamilyPair) {
        val pairs = Json.parseToJsonElement(read("pairs") ?: "{}").jsonObject
        if (pair.id in pairs) return
        val queued = pending() + recipients().map { notice(it, "family_added") }
        save(mapOf(
            "pairs" to JsonObject(pairs + (pair.id to pair.data)).toString(),
            "notices" to JsonArray(queued).toString(),
            "joinedAnnouncement" to pair.id,
        ))
    }
    /** Called only by this phone's settings UI; no network message is accepted here. */
    @Synchronized fun setPolicy(category: FamilyCategory, mode: FamilyMode) {
        val queued = pending().toMutableList()
        if (policy().lowered(category, mode)) recipients().forEach { queued += notice(it, "lowered_${category.wire}") }
        save(mapOf("policy_${category.wire}" to mode.name, "notices" to JsonArray(queued).toString()))
    }
    @Synchronized fun queueNotice(pair: String, category: String) {
        val fixed = requireNotNull(FamilyCategory.fromWire(category))
        if (pair !in recipients()) return
        save(mapOf("notices" to JsonArray(pending() + notice(pair, "operation_${fixed.wire}")).toString()))
    }
    @Synchronized fun acknowledge(id: String) {
        save(mapOf("notices" to JsonArray(pending().filter { it.str("id") != id }).toString()))
    }
}
