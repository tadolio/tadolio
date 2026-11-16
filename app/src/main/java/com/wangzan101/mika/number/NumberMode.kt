// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.serialization.json.*

internal object NumberMode {
    /**
     * Reports only when the mode differs from the last value the hub accepted. A failed report
     * never fails a fetch or send; it is retried on the next call.
     */
    fun report(number: String, kind: ProviderConfig.Kind?, last: String?, save: (String) -> Unit,
               post: (String, JsonObject) -> Unit) {
        if (number.isEmpty()) return
        val community = kind == ProviderConfig.Kind.Community
        val key = "$number:$community"
        if (key == last) return
        runCatching { post("/v1/number/mode", buildJsonObject { put("community", community) }) }
            .onSuccess { save(key) }
    }
}
