// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.core.model.KeyFailure
import com.wangzan101.mika.core.model.keyFailure
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

    fun validate(base: String, model: String, key: String): Result = validate(base, model, listOf(key))

    fun validate(base: String, model: String, keys: List<String>): Result {
        if (keys.size !in 1..5 || keys.any { it.isBlank() }) return Result.Failed
        for (key in keys) {
            val (result, failure) = checkKey(base, model, key)
            if (result == Result.Valid || failure == KeyFailure.other) return result
        }
        return Result.Failed
    }

    private fun checkKey(base: String, model: String, key: String): Pair<Result, KeyFailure> = runCatching {
        if (!MinerPolicy.validApi(base, model, key, MinerPolicy.DEFAULT_DAILY_TOKENS, true)) return Result.Failed to KeyFailure.other
        // OpenRouter /models is public and does NOT validate a key. /key requires authentication.
        val openRouter = base.trimEnd('/') == MinerPlatforms.all.first().base
        val path = if (openRouter) "/key" else "/models"
        val request = Request.Builder().url(base.trimEnd('/') + path)
            .header("Authorization", "Bearer $key").build()
        client.newCall(request).execute().use { response ->
            val source = response.body?.source() ?: return Result.Failed to KeyFailure.other
            if (source.request(8_000_001)) return Result.Failed to KeyFailure.other
            val body = source.readUtf8()
            if (!response.isSuccessful) return Result.Failed to keyFailure(response.code, body)
            val data = Json.parseToJsonElement(body).jsonObject["data"]
            val valid = if (openRouter) data is JsonObject &&
                (data["limit_remaining"]?.jsonPrimitive?.doubleOrNull?.let { it > 0 } != false) else
                data is JsonArray && data.any { it.jsonObject["id"]?.jsonPrimitive?.content == model }
            if (valid) Result.Valid to KeyFailure.other else Result.Failed to
                if (openRouter && data is JsonObject && data["limit_remaining"]?.jsonPrimitive?.doubleOrNull?.let { it <= 0 } == true) KeyFailure.exhausted else KeyFailure.other
        }
    }.getOrDefault(Result.Failed to KeyFailure.other)
}
