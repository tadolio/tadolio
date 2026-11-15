// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

import kotlinx.serialization.json.*

fun interface GovernancePort {
    suspend fun report(kind: String, id: String, reason: String): String
}

/** Reuses the device-signed number transport. */
class HubGovernancePort(private val call: suspend (String, JsonObject?) -> JsonElement) : GovernancePort {
    suspend fun mine(): List<GovernanceEffect> = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(
        call("/v1/gov/mine", null).jsonObject.getValue("effects"))

    suspend fun appeal(id: String, reason: String): String =
        call("/v1/gov/appeal", buildJsonObject {
            put("case", id)
            put("reason", reason)
        }).jsonObject.getValue("id").jsonPrimitive.content

    suspend fun jury(): List<JuryCase> = call("/v1/gov/jury", null).jsonObject.getValue("cases").jsonArray.map { element ->
        val row = element.jsonObject
        JuryCase(row.getValue("id").jsonPrimitive.content, row.getValue("snapshot").toString(),
            row.getValue("reports").jsonArray.map { it.jsonObject.getValue("reason").jsonPrimitive.content })
    }

    suspend fun vote(id: String, ballot: JuryBallot) {
        call("/v1/gov/vote", buildJsonObject {
            put("case", id)
            put("vote", ballot.vote)
            put("reason", ballot.reason)
        })
    }

    override suspend fun report(kind: String, id: String, reason: String): String =
        call("/v1/gov/report", buildJsonObject {
            put("kind", kind)
            put("id", id)
            put("reason", reason)
        }).jsonObject.getValue("id").jsonPrimitive.content
}
