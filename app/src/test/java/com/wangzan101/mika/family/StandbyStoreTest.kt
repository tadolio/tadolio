// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class StandbyStoreTest {
    /** A transactional in-memory preferences implementation; never invokes Android stubs. */
    private class Disk {
        val data = mutableMapOf<String, Any?>()
        var fail = false
        @Suppress("UNCHECKED_CAST")
        fun prefs(): SharedPreferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> {
                    val staged = mutableMapOf<String, Any?>()
                    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, m, a ->
                        when {
                            m.name.startsWith("put") -> { staged[a!![0] as String] = a[1]; proxy }
                            m.name == "remove" -> { staged[a!![0] as String] = null; proxy }
                            m.name == "commit" -> if (fail) false else { staged.forEach { (k,v) -> if (v == null) data.remove(k) else data[k] = v }; true }
                            else -> error(m.name)
                        }
                    }
                }
                else -> if (method.name.startsWith("get")) data[args!![0] as String] ?: args[1] else error(method.name)
            }
        } as SharedPreferences
    }
    @Test fun repeatUsesDurableVerdictWithoutAnotherBudgetSlotOrRawText() {
        val disk = Disk(); val store = StandbyStore(disk.prefs())
        store.setLimit(1)
        val text = "secret SMS body https://fraud.invalid"
        assertNull(store.cached(text))
        assertTrue(store.reserve(text))
        store.cache(text, ScamVerdict.Suspicious, true)
        val restarted = StandbyStore(disk.prefs())
        assertEquals(ScamVerdict.Suspicious to true, restarted.cached(text))
        assertFalse(restarted.reserve("another body"))
        assertFalse(disk.data.toString().contains(text))
        assertFalse(disk.data.toString().contains("fraud.invalid"))
        disk.data["verdict_cache"] = disk.data["verdict_cache"].toString().replace(java.time.LocalDate.now().toString(), "2000-01-01")
        assertNull(store.cached(text))
    }
    @Test fun allVerdictsCanBeReusedAndFailedCacheCannotAuthorizeReuse() {
        val disk = Disk(); val store = StandbyStore(disk.prefs())
        for (verdict in ScamVerdict.values()) {
            store.cache(verdict.name, verdict, false)
            assertEquals(verdict to false, store.cached(verdict.name))
        }
        disk.fail = true
        assertThrows(IllegalStateException::class.java) { store.cache("failed", ScamVerdict.Suspicious, false) }
        assertNull(store.cached("failed"))
    }
    @Test fun combinedSwitchPersistsBothFlagsAndEpoch() {
        val disk = Disk()
        val store = StandbyStore(disk.prefs())
        store.setChecking(true)
        val restored = StandbyStore(disk.prefs())
        assertTrue(restored.enabled())
        assertTrue(restored.sms())
        assertTrue(restored.enabledSince() > 0)
        restored.setChecking(false)
        assertFalse(store.enabled())
        assertFalse(store.sms())
        assertEquals(30, store.limit())
    }
    @Test fun failedCombinedSwitchPreservesPreviousFlags() {
        val disk = Disk()
        val store = StandbyStore(disk.prefs())
        disk.fail = true
        assertThrows(IllegalStateException::class.java) { store.setChecking(true) }
        assertFalse(store.enabled())
        assertFalse(store.sms())
    }
    @Test fun defaultOffLimitAndPersistentDedup() {
        val disk = Disk(); val store = StandbyStore(disk.prefs())
        assertFalse(store.enabled()); assertFalse(store.sms()); assertFalse(store.consent()); assertEquals(30, store.limit())
        store.set("enabled", true); store.set("sms", true)
        assertTrue(store.enabledSince() > 0)
        store.setLimit(2)
        assertTrue(store.reserve("raw secret one")); assertFalse(StandbyStore(disk.prefs()).reserve("raw secret one"))
        assertTrue(store.reserve("raw secret two")); assertFalse(store.reserve("raw secret three"))
        disk.data["day"] = "2000-01-01"
        assertFalse(store.reserve("raw secret one"))
        assertTrue(store.reserve("raw secret four"))
        assertFalse(disk.data.toString().contains("raw secret"))
        store.clear(); assertFalse(store.reserve("raw secret four"))
    }
    @Test fun historyContainsOnlyTimeSuffixConclusionAndFixedReasonAndClears() {
        val disk = Disk(); val store = StandbyStore(disk.prefs())
        store.record("13800138000", ScamResult(ScamVerdict.Normal,"normal", ""))
        assertTrue(store.records().isEmpty())
        store.record("13800138000", ScamResult(ScamVerdict.Suspicious,"Fixed category", "Never persist this advice"), true)
        val restarted = StandbyStore(disk.prefs())
        assertEquals("8000", restarted.records().single().sender)
        assertTrue(restarted.records().single().local)
        val raw = disk.data["records"].toString()
        assertFalse(raw.contains("13800138000")); assertFalse(raw.contains("Never persist"))
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(raw).let { it as kotlinx.serialization.json.JsonArray }.single().let { it as kotlinx.serialization.json.JsonObject }.keys
        assertEquals(setOf("at","sender","verdict","reason"), keys)
        restarted.clear(); assertTrue(restarted.records().isEmpty())
    }
    @Test fun failedReservationNeverAuthorizesSending() {
        val disk = Disk(); val store = StandbyStore(disk.prefs()); disk.fail = true
        assertFalse(store.reserve("do not send")); assertTrue(disk.data.isEmpty())
    }
}
