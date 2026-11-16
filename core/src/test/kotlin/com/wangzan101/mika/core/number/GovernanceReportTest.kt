// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.tool.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GovernanceReportTest {
    @Test fun unattendedReadOnlyReportsWithoutConfirmationUsingSignedTransport() = runTest {
        var calls = 0
        val port = object : NumberPort {
            override val governance = HubGovernancePort { path, body ->
                assertEquals("/v1/gov/report", path)
                assertEquals("post", body!!.getValue("kind").jsonPrimitive.content)
                assertEquals("p", body.getValue("id").jsonPrimitive.content)
                assertEquals("Specific evidence", body.getValue("reason").jsonPrimitive.content)
                calls++
                buildJsonObject { put("id", "case-1") }
            }
            override fun inbox() = emptyList<NumberPort.Item>()
            override suspend fun send(number: String, kind: String, subject: String, text: String, thread: String?) = Unit
        }
        val tools = toolDispatcher(FakeScreen(), number = port,
            runSpec = RunSpec(RunId("r"), "inspect", "c", readOnly = true))
        assertTrue(tools.definitions().any { it.name == "hivo_report" })
        val args = buildJsonObject { put("kind", "post"); put("id", "p"); put("reason", "Specific evidence") }
        val judge = ConfirmDecision(tools, ConfirmationGate(), ApprovalSettings { true },
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended), autonomous = true)
        assertEquals(Decision.Clear, judge.judge("hivo_report", args, Origin.Model(1)))
        val result = tools.executeRead(Part.ToolCall("r", "hivo_report", args.toString()))
        assertEquals(JsonPrimitive("case-1"), result.content["id"])
        assertEquals(1, calls)
        for (reason in listOf("", "x".repeat(501))) {
            val invalid = JsonObject(args + ("reason" to JsonPrimitive(reason)))
            val rejected = tools.executeRead(Part.ToolCall("bad", "hivo_report", invalid.toString()))
            assertTrue(rejected.content.containsKey("error"))
        }
        assertEquals(1, calls)
    }

    @Test fun juryTransportPreservesSnapshotAndAllReasons() = runTest {
        val calls = mutableListOf<String>()
        val port = HubGovernancePort { path, body ->
            calls += path
            if (path == "/v1/gov/jury") {
                assertNull(body)
                Json.parseToJsonElement("""{"cases":[{"id":"c","snapshot":{"text":"target"},"reports":[{"reason":"first"},{"reason":"second"}]}]}""")
            } else {
                assertEquals("c", body!!.getValue("case").jsonPrimitive.content)
                assertEquals("dismiss", body.getValue("vote").jsonPrimitive.content)
                assertEquals("uncertain", body.getValue("reason").jsonPrimitive.content)
                JsonObject(emptyMap())
            }
        }
        val cases = port.jury()
        assertEquals(listOf("first", "second"), cases.single().reports)
        assertEquals("target", Json.parseToJsonElement(cases.single().snapshot).jsonObject.getValue("text").jsonPrimitive.content)
        port.vote(cases.single().id, JuryBallot("dismiss", "uncertain"))
        assertEquals(listOf("/v1/gov/jury", "/v1/gov/vote"), calls)
    }

    @Test fun emptyJuryEnvelopeReturnsNoCases() = runTest {
        val port = HubGovernancePort { path, body ->
            assertEquals("/v1/gov/jury", path)
            assertNull(body)
            Json.parseToJsonElement("""{"cases":[]}""")
        }
        assertTrue(port.jury().isEmpty())
    }

    @Test fun unavailableWithoutGovernancePort() {
        assertFalse(toolDispatcher(FakeScreen()).definitions().any { it.name == "hivo_report" })
    }
}
