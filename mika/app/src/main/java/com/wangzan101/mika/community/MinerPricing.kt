// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.Request

internal object MinerPricing {
    data class Quote(val input: Double, val output: Double)
    private val http = HubTls.builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build()

    /** Public model catalogue only. No account credentials or user content are attached. */
    fun lookup(base: String, model: String): Quote? = runCatching {
        if (URI(base).host != "openrouter.ai" || model.isBlank()) return null
        http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/models").build()).execute().use {
            if (!it.isSuccessful) return null
            val source = it.body!!.source()
            if (source.request(8_000_001)) return null
            parse(source.readUtf8(),model)
        }
    }.getOrNull()

    fun parse(body: String, model: String): Quote? = runCatching {
        val entry = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonArray
            .firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == model }?.jsonObject ?: return null
        val pricing = entry.getValue("pricing").jsonObject
        // Additional per-request/image charges cannot be represented as a token-only estimate.
        if (pricing.any { (name,value) -> name !in setOf("prompt","completion","input_cache_read","input_cache_write") &&
                    value.jsonPrimitive.doubleOrNull != 0.0 }) return null
        val input = pricing.getValue("prompt").jsonPrimitive.double * 1_000_000
        val output = pricing.getValue("completion").jsonPrimitive.double * 1_000_000
        if (estimate(1,input,output) == null) return null
        Quote(input,output)
    }.getOrNull()

    /** Total budget includes input and output. Show the range between the two extreme mixes. */
    fun estimate(limit: Long?, input: Double?, output: Double?): Pair<Double,Double>? {
        if (limit == null || limit <= 0 || input == null || output == null ||
            !input.isFinite() || !output.isFinite() || input < 0 || output < 0) return null
        val low = limit / 1_000_000.0 * minOf(input,output)
        val high = limit / 1_000_000.0 * maxOf(input,output)
        if (!low.isFinite() || !high.isFinite()) return null
        return low to high
    }
}
