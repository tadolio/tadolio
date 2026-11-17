// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import kotlinx.serialization.json.*

internal object MinerPayees {
    fun rows(config: JsonObject, ownNumber: String): List<Pair<String, String>> {
        val saved = config["numbers"] as? JsonArray
        if (saved != null) return saved.map { it.jsonObject.let { row ->
            row.getValue("number").jsonPrimitive.content to row["percent"]?.jsonPrimitive?.content.orEmpty()
        } }.ifEmpty { listOf("" to "") }
        return listOf((config["number"]?.jsonPrimitive?.content ?: ownNumber) to "")
    }

    fun normalize(rows: List<Pair<String, String>>): JsonArray {
        val live = rows.filter { it.first.isNotBlank() }
        require(rows.size <= 5 && live.all { it.first.matches(Regex("[0-9]{8}")) })
        require(live.map { it.first }.distinct().size == live.size)
        val automatic = live.all { it.second.isBlank() }
        require(automatic || live.all { it.second.toIntOrNull() in 0..100 } && live.sumOf { it.second.toInt() } == 100)
        return buildJsonArray {
            live.forEachIndexed { index, row -> add(buildJsonObject {
                put("number", row.first)
                put("percent", if (automatic) 100 / live.size + (if (index == 0) 100 % live.size else 0) else row.second.toInt())
            }) }
        }
    }
}
