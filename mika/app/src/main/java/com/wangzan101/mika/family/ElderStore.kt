// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

internal class ElderStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("hivo_elder_local", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()
    private val _revision = MutableStateFlow(0)
    val revision = _revision.asStateFlow()
    fun setEnabled(value: Boolean) {
        val edit = prefs.edit().putBoolean("enabled", value)
        if (edit.commit()) _enabled.value = value
    }
    private fun entries(): JSONArray = runCatching { JSONArray(prefs.getString("completed", "[]")) }.getOrDefault(JSONArray())
    @Synchronized fun record(id: String, endedAt: Long, steps: Int, answer: String, completed: Boolean) {
        val all = entries()
        if ((0 until all.length()).any { all.getJSONObject(it).getString("id") == id }) return
        all.put(JSONObject().put("id", id).put("at", endedAt).put("steps", steps.coerceAtLeast(0)).put("done", completed))
        val recent = JSONArray(prefs.getString("recent", "[]"))
        val next = JSONArray().put(answer.take(2000))
        for (i in 0 until minOf(recent.length(), 2)) next.put(recent.getString(i))
        if (prefs.edit().putString("completed", all.toString()).putString("recent", next.toString()).commit()) _revision.value++
    }
    @Synchronized fun weekTasks(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val start = TimeSavings.weekStart(now, zone)
        val all = entries()
        return (0 until all.length()).sumOf { i -> all.getJSONObject(i).let {
            if (it.getLong("at") in start..now && it.optBoolean("done", false)) 1L else 0L
        } }
    }
    @Synchronized fun recent(): List<String> = runCatching {
        val all = JSONArray(prefs.getString("recent", "[]"))
        (0 until minOf(3, all.length())).map { all.getString(it) }
    }.getOrDefault(emptyList())
    companion object {
        @Volatile private var instance: ElderStore? = null
        fun get(context: Context): ElderStore = instance ?: synchronized(this) {
            instance ?: ElderStore(context.applicationContext).also { instance = it }
        }
    }
}
