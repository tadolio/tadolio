// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.core.model.KeyFailure
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MinerKeysTest {
    @get:Rule val tmp = TemporaryFolder()
    private val prefs = MemoryPrefs()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }
    private var at = 1_800_000_000_000L
    private fun store() = MinerStore(java.io.File(tmp.root, "synthetic.encrypted"), cipher, prefs) { at }
    private fun config() = buildJsonObject {
        put("key", "synthetic-one")
        putJsonArray("keys") { add("synthetic-one"); add("synthetic-two"); add("synthetic-three") }
        put("limit", 2000000)
        put("nodeId", "synthetic-identity")
    }

    @Test fun rotatesSameDeploymentKeysWithoutChangingUsageOrAdmission() {
        val store = store()
        val cfg = config()
        store.save(cfg)
        store.enabled = true
        store.phase = MinerState.ONLINE
        assertTrue(store.reserve(100, 2000000))
        val seen = mutableListOf<String>()
        val reply = store.upstream(cfg) { key -> seen += key; ModelHttp.Reply(if (key.endsWith("three")) 200 else 401, null, "{}") }
        assertTrue(reply.ok)
        assertEquals(listOf("synthetic-one", "synthetic-two", "synthetic-three"), seen)
        assertEquals(3, store.activeKey)
        assertEquals(MinerState.ONLINE, store.phase)
        assertEquals(100L, store.used())
        assertEquals(cfg["nodeId"], store.read()["nodeId"])
    }

    @Test fun allUnavailablePausesAndCooldownResumesWithoutResettingBudget() {
        val store = store()
        store.save(config()); store.enabled = true
        assertThrows(IllegalStateException::class.java) { store.upstream(config()) { ModelHttp.Reply(429, null, "{}") } }
        assertEquals(MinerState.KEY_BAD, store.phase)
        assertTrue(store.enabled)
        assertTrue(store.keyStatuses().all { it == KeyFailure.rate_limited })
        val restored = store()
        restored.refreshKeyState()
        assertEquals(MinerState.KEY_BAD, restored.phase)
        at += 60000
        restored.refreshKeyState()
        assertEquals(MinerState.CONNECTING, restored.phase)
        assertTrue(restored.upstream(config()) { ModelHttp.Reply(200, null, "{}") }.ok)
    }

    @Test fun legacySingleKeyAndNonCredentialErrorsRemainCompatible() {
        val store = store()
        val legacy = buildJsonObject { put("key", "synthetic-legacy") }
        assertEquals(listOf("synthetic-legacy"), store.keys(legacy))
        var calls = 0
        assertEquals(503, store.upstream(config()) { calls++; ModelHttp.Reply(503, null, "invalid api key") }.status)
        assertEquals(1, calls)
        assertTrue(store.keyStatuses(config()).all { it == KeyFailure.other })
        assertThrows(IllegalStateException::class.java) { store.upstream(config()) { error("synthetic-network") } }
        assertTrue(store.keyStatuses(config()).all { it == KeyFailure.other })
    }

    @Test fun invalidAndExhaustedKeysStayPausedAcrossRestartUntilNextLocalDate() {
        val store = store()
        store.save(config())
        assertThrows(IllegalStateException::class.java) {
            store.upstream(config()) { key -> ModelHttp.Reply(if (key.endsWith("one")) 401 else 402, null, "{}") }
        }
        val restored = store()
        assertEquals(listOf(KeyFailure.invalid, KeyFailure.exhausted, KeyFailure.exhausted), restored.keyStatuses())
        restored.refreshKeyState()
        assertEquals(MinerState.KEY_BAD, restored.phase)
        at += 86400000
        restored.refreshKeyState()
        assertEquals(MinerState.CONNECTING, restored.phase)
        assertTrue(restored.keyStatuses().all { it == KeyFailure.other })
    }
}
