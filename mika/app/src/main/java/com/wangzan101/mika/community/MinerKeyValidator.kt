// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.R
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Direct-to-platform check. Never logs credentials, response bodies or exception messages. */
internal class MinerKeyValidator(private val http: OkHttpClient = HubTls.builder().build()) {
    enum class Result(val message: Int?) {
        Valid(null), Failed(R.string.miner_key_failed)
    }
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(12, TimeUnit.SECONDS).build()

    fun validate(base: String, model: String, key: String): Result = runCatching {
        if (!MinerPolicy.validApi(base, model, key, MinerPolicy.DEFAULT_DAILY_TOKENS, true)) return Result.Failed
        // OpenRouter /models is public and does NOT validate a key. /key requires authentication.
        val openRouter = base.trimEnd('/') == MinerPlatforms.all.first().base
        val path = if (openRouter) "/key" else "/models"
        val request = Request.Builder().url(base.trimEnd('/') + path)
            .header("Authorization", "Bearer $key").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return Result.Failed
            val source = response.body?.source() ?: return Result.Failed
            if (source.request(8_000_001)) return Result.Failed
            val data = Json.parseToJsonElement(source.readUtf8()).jsonObject["data"]
            val valid = if (openRouter) data is JsonObject &&
                (data["limit_remaining"]?.jsonPrimitive?.doubleOrNull?.let { it > 0 } != false) else
                data is JsonArray && data.any { it.jsonObject["id"]?.jsonPrimitive?.content == model }
            if (valid) Result.Valid else Result.Failed
        }
    }.getOrDefault(Result.Failed)
}
