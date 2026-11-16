// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NumberModeTest {
    private var saved: String? = null
    private val reports = mutableListOf<JsonObject>()
    private var failing = false

    private fun report(number: String, kind: ProviderConfig.Kind?) =
        NumberMode.report(number, kind, saved, { saved = it }) { path, body ->
            assertEquals("/v1/number/mode", path)
            assertEquals(setOf("community"), body.keys)
            if (failing) error("hub down")
            reports += body
        }

    @Test fun modeSwitchReportsBothDirectionsWithoutTargetingAnotherNumber() {
        report("", ProviderConfig.Kind.Community)
        assertTrue(reports.isEmpty())
        report("12345678", ProviderConfig.Kind.Community)
        report("12345678", ProviderConfig.Kind.OpenAiCompat)
        assertEquals(listOf(true, false), reports.map { it.getValue("community").jsonPrimitive.boolean })
    }

    @Test fun unchangedModeIsNotReportedAgain() {
        repeat(3) { report("12345678", ProviderConfig.Kind.Community) }
        assertEquals(1, reports.size)
        report("87654321", ProviderConfig.Kind.Community)
        assertEquals(2, reports.size)
    }

    @Test fun failedReportDoesNotThrowAndRetriesLater() {
        failing = true
        report("12345678", ProviderConfig.Kind.Community)
        assertNull(saved)
        failing = false
        report("12345678", ProviderConfig.Kind.Community)
        assertEquals(1, reports.size)
    }
}
