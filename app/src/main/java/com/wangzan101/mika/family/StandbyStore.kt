// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.content.Context
import kotlinx.serialization.json.*
import java.time.LocalDate

internal data class StandbyRecord(val at: Long, val sender: String, val verdict: ScamVerdict, val reason: String, val local: Boolean = false)

internal class StandbyStore internal constructor(private val prefs: android.content.SharedPreferences) {
    fun enabled() = prefs.getBoolean("enabled", false)
    fun sms() = prefs.getBoolean("sms", false)
    fun consent() = prefs.getBoolean("community", false)
    fun limit() = prefs.getInt("limit", 30).coerceIn(1, 500)
    fun enabledSince() = prefs.getLong("enabled_since", Long.MAX_VALUE)
    fun set(key: String, value: Boolean) {
        require(key in setOf("enabled", "sms", "community"))
        val edit = prefs.edit().putBoolean(key, value)
        if (value && key in setOf("enabled", "sms")) edit.putLong("enabled_since", System.currentTimeMillis())
        check(edit.commit())
    }
    fun setChecking(value: Boolean) {
        val edit = prefs.edit().putBoolean("enabled", value).putBoolean("sms", value)
        if (value) edit.putLong("enabled_since", System.currentTimeMillis())
        check(edit.commit())
    }
    fun setLimit(value: Int) { check(prefs.edit().putInt("limit", value.coerceIn(1, 500)).commit()) }
    @Synchronized fun reserve(text: String): Boolean {
        val seen = Json.parseToJsonElement(prefs.getString("seen", "[]")!!).jsonArray.map { it.jsonPrimitive.content }
        val hash = StandbyRules.fingerprint(text)
        if (hash in seen) return false
        val old = StandbyBudget(prefs.getString("day", "").orEmpty(), prefs.getStringSet("hashes", emptySet()).orEmpty().toSet())
        val next = old.reserve(text, LocalDate.now(), limit()) ?: return false
        return prefs.edit().putString("day", next.day).putStringSet("hashes", next.hashes)
            .putString("seen", JsonArray((seen + hash).takeLast(4096).map(::JsonPrimitive)).toString()).commit()
    }
    // Cache contains only a digest, enum verdict and fixed category; expires each day.
    @Synchronized fun cached(text: String): Pair<ScamVerdict, Boolean>? = runCatching {
        val o = Json.parseToJsonElement(prefs.getString("verdict_cache", "{}")!!).jsonObject
        if (o["day"]?.jsonPrimitive?.content != LocalDate.now().toString()) return null
        val entry = o[StandbyRules.fingerprint(text)]?.jsonObject ?: return null
        ScamVerdict.valueOf(entry.getValue("verdict").jsonPrimitive.content) to entry.getValue("local").jsonPrimitive.boolean
    }.getOrNull()
    @Synchronized fun cache(text: String, verdict: ScamVerdict, local: Boolean) {
        val today = LocalDate.now().toString()
        val old = runCatching { Json.parseToJsonElement(prefs.getString("verdict_cache", "{}")!!).jsonObject }.getOrNull()
        val next = buildJsonObject {
            put("day", today)
            if (old?.get("day")?.jsonPrimitive?.content == today) old.entries.filter { it.key != "day" }.takeLast(499).forEach { put(it.key, it.value) }
            put(StandbyRules.fingerprint(text), buildJsonObject {
                put("verdict", verdict.name); put("local", local); put("category", StandbyRules.category(text))
            })
        }
        check(prefs.edit().putString("verdict_cache", next.toString()).commit())
    }
    @Synchronized fun records(): List<StandbyRecord> = runCatching {
        Json.parseToJsonElement(prefs.getString("records", "[]")!!).jsonArray.map {
            val o = it.jsonObject
            StandbyRecord(o.getValue("at").jsonPrimitive.long, o.getValue("sender").jsonPrimitive.content,
                if (o.getValue("verdict").jsonPrimitive.content == "LocalRisk") ScamVerdict.Suspicious else ScamVerdict.valueOf(o.getValue("verdict").jsonPrimitive.content),
                o.getValue("reason").jsonPrimitive.content, o.getValue("verdict").jsonPrimitive.content == "LocalRisk")
        }
    }.getOrDefault(emptyList())
    @Synchronized fun record(sender: String, result: ScamResult, local: Boolean = false) {
        if (result.verdict == ScamVerdict.Normal) return
        val next = (listOf(StandbyRecord(System.currentTimeMillis(), StandbyRules.suffix(sender), result.verdict,
            StandbyRules.safe(result.reason).replace(Regex("[\\r\\n]+"), " ").take(300), local)) + records()).take(200)
        check(prefs.edit().putString("records", JsonArray(next.map { r -> buildJsonObject {
            put("at", r.at); put("sender", r.sender); put("verdict", if (r.local) "LocalRisk" else r.verdict.name); put("reason", r.reason)
        } }).toString()).commit())
    }
    @Synchronized fun clear() { check(prefs.edit().remove("records").commit()) }
    companion object {
        @Volatile private var instance: StandbyStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: StandbyStore(context.applicationContext.getSharedPreferences("hivo_standby", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
