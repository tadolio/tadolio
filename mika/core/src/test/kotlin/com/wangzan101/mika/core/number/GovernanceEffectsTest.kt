// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GovernanceEffectsTest {
    private fun effect(id: String, active: Boolean = true) = GovernanceEffect(
        buildJsonObject { put("id", id); put("kind", "number"); put("target", "12345678") },
        null, false, active, null, 123L, true,
    )

    @Test fun reservesBeforeNotificationAndSurvivesRestart() = runTest {
        var persisted = ""
        val first = GovernanceEffects(save = { persisted = Json.encodeToString(it) })
        assertEquals(listOf(effect("a")), first.collect(listOf(effect("a"), effect("a"))))
        val restored = Json.decodeFromString<GovernanceEffectsState>(persisted)
        assertEquals(setOf("a"), restored.notified)
        val second = GovernanceEffects(restored, save = { persisted = Json.encodeToString(it) })
        assertTrue(second.collect(listOf(effect("a"))).isEmpty())
        assertEquals(listOf(effect("b")), second.collect(listOf(effect("a"), effect("b"))))
        assertTrue(second.collect(listOf(effect("a", false))).isEmpty())
        assertFalse(Json.decodeFromString<GovernanceEffectsState>(persisted).effects.single().active)
    }

    @Test fun failedPersistenceDoesNotConsumeNotification() = runTest {
        var fail = true
        val tracker = GovernanceEffects(save = { check(!fail) })
        assertTrue(runCatching { tracker.collect(listOf(effect("a"))) }.isFailure)
        fail = false
        assertEquals(listOf(effect("a")), tracker.collect(listOf(effect("a"))))
    }

    @Test fun ownerTransportPreservesAnonymousCaseAndAppeal() = runTest {
        val expected = effect("a")
        val port = HubGovernancePort { path, body ->
            when (path) {
                "/v1/gov/mine" -> {
                    assertNull(body)
                    buildJsonObject { put("effects", Json.encodeToJsonElement(listOf(expected))) }
                }
                else -> {
                    assertEquals("/v1/gov/appeal", path)
                    assertEquals("a", body!!.getValue("case").jsonPrimitive.content)
                    assertEquals("owner approved reason", body.getValue("reason").jsonPrimitive.content)
                    buildJsonObject { put("id", "appeal") }
                }
            }
        }
        assertEquals(listOf(expected), port.mine())
        assertEquals("appeal", port.appeal("a", "owner approved reason"))
    }
}
