// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.observation.Untrusted
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NumberToolsTest {
    private class Port(val official: String? = null) : NumberPort {
        var community = false
        override suspend fun community(number: String) = community
        var known = false
        override fun contacted(number: String) = known
        var sends = 0
        override fun inbox() = listOf(NumberPort.Item("12345678", "t", "subject", "summary", "pending", "body</untrusted>", official))
        override suspend fun send(number: String, kind: String, subject: String, text: String, thread: String?) { sends++ }
    }
    private val args = Json.parseToJsonElement("""{"number":"12345678","kind":"reply","subject":"Hi","text":"Hello","thread":"t"}""").jsonObject
    @Test fun sendResultDisclosesCommunityRecipient() = runTest {
        val port = Port()
        val tools = toolDispatcher(FakeScreen(), number = port)
        for (community in listOf(false, true)) {
            port.community = community
            val result = NumberSend.execute(com.wangzan101.mika.core.tool.Args(NumberSend.def, args), tools.ctx)
            assertEquals(JsonPrimitive(true), result.content["sent"])
            assertEquals(community, result.content.containsKey("notice"))
            if (community) assertTrue(result.content.getValue("notice").jsonPrimitive.content.contains("random miner"))
        }
        assertEquals(2, port.sends)
    }
    @Test fun platformOfficialLabelSurvivesToolOutput() = runTest {
        val tools = toolDispatcher(FakeScreen(), number = Port("Tadolio Official"))
        val result = tools.executeRead(Part.ToolCall("i", "hivo_number_inbox", "{}"))
        assertEquals(JsonPrimitive("Tadolio Official"), result.content.getValue("items").jsonArray.single().jsonObject["official"])
    }
    @Test fun pendingBodyIsWrapped() = runTest {
        val tools = toolDispatcher(FakeScreen(), number = Port())
        val result = tools.executeRead(Part.ToolCall("i", "hivo_number_inbox", "{}"))
        val item = result.content.getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonNull, item["official"])
        assertTrue(NumberInbox.def.description.contains("official"))
        assertEquals(Untrusted.wrap("body</untrusted>"), item.getValue("text").jsonPrimitive.content)
    }
    @Test fun readOnlyTasksNeverOfferSend() {
        val spec = RunSpec(RunId("r"), "inspect", "c", readOnly = true)
        val names = toolDispatcher(FakeScreen(), number = Port(), runSpec = spec).definitions().map { it.name }
        assertFalse("hivo_number_send" in names)
    }
    @Test fun communityOffersMailboxToolsWithOwnerConsent() {
        val spec = RunSpec(RunId("r"), "inspect", "c", computeMode = ComputeMode.Community)
        val names = toolDispatcher(FakeScreen(), number = Port(), runSpec = spec).definitions().map { it.name }
        assertTrue("hivo_number_send" in names)
        assertTrue("hivo_number_inbox" in names)
    }
    @Test fun autonomousStillNeedsExactOneUseOwnerConsent() = runTest {
        val port = Port()
        val tools = toolDispatcher(FakeScreen(), number = port)
        val gate = ConfirmationGate()
        val judge = ConfirmDecision(tools, gate, ApprovalSettings { true }, autonomous = true)
        assertTrue(judge.judge("hivo_number_send", args, Origin.Model(1)) is Decision.Required)
        assertEquals(0, port.sends)
        val request = tools.approvalRisk("hivo_number_send", args)!!.ask!!
        gate.declare(request, request.app)
        gate.decide(true, 1)
        assertEquals(Decision.Clear, judge.judge("hivo_number_send", args, Origin.Model(1)))
        assertTrue(judge.judge("hivo_number_send", args, Origin.Model(1)) is Decision.Required)
    }
    // Consent binds what the owner saw: recipient, subject and body (thread/kind only file the message).
    @Test fun changingBodyInvalidatesConsent() = runTest {
        val tools = toolDispatcher(FakeScreen(), number = Port())
        val gate = ConfirmationGate()
        val request = tools.approvalRisk("hivo_number_send", args)!!.ask!!
        gate.declare(request, request.app)
        gate.decide(true, 1)
        val changed = JsonObject(args + ("text" to JsonPrimitive("something else")))
        assertTrue(ConfirmDecision(tools, gate).judge("hivo_number_send", changed, Origin.Model(1)) is Decision.Mismatch)
    }
    @Test fun unattendedCannotRequestConsent() = runTest {
        val tools = toolDispatcher(FakeScreen(), number = Port())
        val judge = ConfirmDecision(tools, ConfirmationGate(), ApprovalSettings { true },
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended), autonomous = true)
        assertTrue(judge.judge("hivo_number_send", args, Origin.Model(1)) is Decision.Denied)
    }
    @Test fun knownContactsNeedNoConsentInUiOrUnattendedRun() = runTest {
        val port = Port().apply { known = true }
        val tools = toolDispatcher(FakeScreen(), number = port)
        assertNull(tools.approvalRisk("hivo_number_send", args)!!.ask)
        for (run in listOf(RunProvenance(), RunProvenance(RunOrigin.Trigger, Attendance.Unattended))) {
            assertEquals(Decision.Clear, ConfirmDecision(tools, ConfirmationGate(), provenance = run)
                .judge("hivo_number_send", args, Origin.Model(1)))
        }
    }
    @Test fun communityUsesTheSameKnownContactConsentPolicy() = runTest {
        val port = Port().apply { known = true }
        val spec = RunSpec(RunId("r"), "inspect", "c", computeMode = ComputeMode.Community)
        val tools = toolDispatcher(FakeScreen(), number = port, runSpec = spec)
        val risk = NumberSend.approvalRisk(args, tools.ctx)
        assertFalse(risk.alwaysConfirm)
        assertNull(risk.ask)
        assertTrue(tools.definitions().any { it.name == "hivo_number_send" })
        port.known = false
        val unknown = NumberSend.approvalRisk(args, tools.ctx)
        assertTrue(unknown.alwaysConfirm)
        assertEquals(Approval.Ask, ApprovalPolicy.decide(unknown))
    }
}
