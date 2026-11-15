// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.*

/** Same Android Keystore AES/GCM storage as BYOK. All credentials and node keys stay on device. */
internal class MinerStore(private val file:File, private val cipher:KeyCipher, private val prefs:SharedPreferences,
                          private val now:()->Long=System::currentTimeMillis) {
    constructor(context:Context):this(context, activeKind(context))
    constructor(context:Context, kind:String):this(File(context.filesDir,if(kind == "operator") "community-operator.encrypted" else "community-miner.encrypted"),KeystoreKeyCipher(),
        context.getSharedPreferences(if(kind == "operator") "hivo_operator" else "hivo_miner",Context.MODE_PRIVATE))
    fun read(): JsonObject = synchronized(configLock) { if(file.exists())
        Json.parseToJsonElement(String(cipher.decrypt(file.readBytes()), Charsets.UTF_8)).jsonObject else buildJsonObject {} }
    fun save(value: JsonObject) = synchronized(configLock) {
        val tmp=File(file.path+".tmp")
        tmp.writeBytes(cipher.encrypt(value.toString().toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }
    fun update(transform: (JsonObject) -> JsonObject): JsonObject = synchronized(configLock) {
        transform(read()).also { save(it) }
    }
    fun changeLimit(limit: Long) = synchronized(configLock) {
        require(limit in 1..MinerPolicy.MAX_DAILY_TOKENS)
        update { JsonObject(it + ("limit" to JsonPrimitive(limit))) }
    }
    var phase: MinerState
        get() = runCatching { MinerState.valueOf(prefs.getString("phase", null).orEmpty()) }.getOrDefault(MinerState.CONNECTING)
        set(value) { check(prefs.edit().putString("phase", value.name).putLong("phaseAt", now()).commit()) }
    var pauseReason: MinerState?
        get() = runCatching { MinerState.valueOf(prefs.getString("pauseReason", null).orEmpty()) }.getOrNull()
        set(value) { check(prefs.edit().putString("pauseReason", value?.name).commit()) }
    fun state(): MinerState {
        if (phase == MinerState.KEY_BAD) return MinerState.KEY_BAD
        pauseReason?.let { return it }
        val freshness = if (phase == MinerState.EXAM) 120_000 else 45_000
        return if (now() - prefs.getLong("phaseAt", 0) > freshness) MinerState.CONNECTING else phase
    }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled",false)
        set(value) { check(prefs.edit().putBoolean("enabled",value).commit()) }
    var autoStart: Boolean
        get() = prefs.getBoolean("autoStart",MinerPolicy.DEFAULT_AUTO_START)
        set(value) { check(prefs.edit().putBoolean("autoStart",value).commit()) }
    fun used(at: Long=now()): Long = synchronized(configLock) {
        if(prefs.getLong("day",-1)==MinerPolicy.day(at)) prefs.getLong("used",0) else 0
    }
    fun reserve(tokens: Long, limit: Long): Boolean = synchronized(configLock) {
        val at = now()
        val next = used(at) + tokens
        val currentLimit = read()["limit"]?.jsonPrimitive?.longOrNull ?: limit
        if (tokens <= 0 || next > minOf(limit, currentLimit)) return@synchronized false
        check(prefs.edit().putLong("day", MinerPolicy.day(at)).putLong("used", next).commit())
        true
    }
    fun refund(tokens: Long, day: Long) = synchronized(configLock) {
        if(prefs.getLong("day",-1)==day)
            check(prefs.edit().putLong("used",(used()-tokens).coerceAtLeast(0)).commit())
    }
    fun reconcile(reserved:Long, actual:Long, day:Long) = synchronized(configLock) {
        if(actual<=0||prefs.getLong("day",-1)!=day)return@synchronized
        val next=(used()-reserved).coerceAtLeast(0)+actual.coerceAtMost(1_000_000_000_000L)
        check(prefs.edit().putLong("used",next).commit())
    }
    fun stats(pending: Double, confirmed: Long) {
        check(prefs.edit().putString("pending",pending.toString()).putLong("confirmed",confirmed)
            .putLong("statsDay",MinerPolicy.day(now())).commit())
    }
    fun pending() = if(prefs.getLong("statsDay",-1)==MinerPolicy.day(now())) prefs.getString("pending","0")!! else "0"
    fun confirmed()=prefs.getLong("confirmed",0)
    companion object {
        private val configLock = Any()
        fun activeKind(context:Context) = context.getSharedPreferences("miner_role",Context.MODE_PRIVATE).getString("kind","compute")!!
        fun select(context:Context, kind:String) {
            require(kind in setOf("compute","operator"))
            check(context.getSharedPreferences("miner_role",Context.MODE_PRIVATE).edit().putString("kind",kind).commit())
        }
    }
}
