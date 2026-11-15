// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.testing.*
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HybridActionBoundaryTest {
    private val clock = "com.google.android.deskclock"
    private class Phone(var pkg: String, var label: String = "safe") : ScreenPort by FakeScreen() {
        val fake = FakeScreen()
        override suspend fun foregroundPackages() = listOf(pkg)
        override suspend fun snapshot() = ScreenSnapshot("s1", pkg, listOf(
            UiElement("@e1", "button", label, 50, 50, listOf(0, 0, 100, 100), null, true, false, false)) + (2..6).map {
                UiElement("@e$it", "text", "safe label $it", 50, 100+it, listOf(0, 100+it, 100, 101+it), null, false, false, false)
            }, 100, 100)
        override suspend fun peek() = snapshot()
        override suspend fun tapXY(x: Int, y: Int) = fake.tapXY(x,y)
        override suspend fun tapRef(ref: String, snapshotId: String) = fake.tapRef(ref,snapshotId)
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = fake.typeText(ref,snapshotId,text)
    }
    private fun call(name: String = "phone_tap", args: String = """{"ref":"@e1","snapshotId":"s1"}""") = Part.ToolCall("c",name,args)
    private fun spec() = RunSpec(RunId("test"), "test", "test", computeMode=ComputeMode.Community)
    private fun executor(d: ToolDispatcher, approvals: ApprovalSettings = ApprovalSettings.ASK) =
        ActionExecutor(d,OperationGuards(40),ConfirmationGate(),{},ActionRecorder(RunSink.NONE),approvals=approvals)

    @Test fun blockedForegroundPromotesAcrossAllActionOriginsWithoutReplay() = runTest {
        for (origin in listOf(Origin.Model(2),Origin.McpTool(2),Origin.Batch(2),Origin.Script(2),Origin.Replay)) {
            val c=HybridCompute(true);val p=Phone("com.eg.android.AlipayGphone")
            val d=toolDispatcher(p,runSpec=spec(),hybridBoundary=HybridScreenBoundary(c){2})
            val out=executor(d).run(call(args="""{"x":50,"y":50}"""),origin)
            assertTrue(out.toString(),out is ActionOutcome.Ran)
            assertEquals(1,p.fake.calls.size);assertFalse(d.community)
            assertEquals(HybridUpgrade(2,HybridReason.AppNotAllowed),c.upgrade)
        }
    }
    @Test fun safeDispatcherReadAndActionStayOnCommunity() = runTest {
        val c=HybridCompute(true);val p=Phone(clock)
        val d=toolDispatcher(p,runSpec=spec(),hybridBoundary=HybridScreenBoundary(c){2})
        d.executeRead(call("phone_observe","{}"))
        assertTrue(executor(d).run(call(),Origin.Model(2)) is ActionOutcome.Ran)
        assertNull(c.upgrade);assertTrue(d.community);assertEquals(1,p.fake.calls.size)
        val node=fakeModel {answer("safe")};val own=fakeModel {answer("private")}
        HybridModel(node,own,c){2}.complete("base",emptyList(),emptyList())
        assertEquals(1,node.requests.size);assertEquals(0,own.requests.size)
    }
    @Test fun irreversibleFreeActionPromotesAndUsesOwnRules() = runTest {
        val c=HybridCompute(true);val p=Phone(clock,"\u53d1\u9001")
        val d=toolDispatcher(p,runSpec=spec(),hybridBoundary=HybridScreenBoundary(c){3})
        d.executeRead(call("phone_observe","{}"));assertNull(c.upgrade)
        val out=executor(d).run(call(),Origin.Model(3))
        assertTrue(out.toString(),out is ActionOutcome.Ran)
        assertEquals(1,p.fake.calls.size)
        assertEquals(HybridUpgrade(3,HybridReason.IrreversibleAction),c.upgrade)
    }
    @Test fun paymentStillRequiresOwnConfirmationAfterUpgrade() = runTest {
        val c=HybridCompute(true);val p=Phone(clock,"\u7acb\u5373\u652f\u4ed8")
        val d=toolDispatcher(p,runSpec=spec(),hybridBoundary=HybridScreenBoundary(c){4})
        d.executeRead(call("phone_observe","{}"))
        val out=executor(d).run(call(),Origin.Model(4))
        assertFalse(out is ActionOutcome.Ran);assertTrue(p.fake.calls.isEmpty())
        assertEquals(HybridReason.IrreversibleAction,c.upgrade?.reason)
    }
    @Test fun sensitiveInputPromotesBeforeTypingAndNeverReturnsToNode() = runTest {
        val c=HybridCompute(true);val p=Phone(clock)
        val d=toolDispatcher(p,runSpec=spec(),hybridBoundary=HybridScreenBoundary(c){5})
        val node=fakeModel {answer("safe")};val own=fakeModel {repeat(2){answer("own")}}
        val model=HybridModel(node,own,c){5}
        model.complete("base",emptyList(),emptyList())
        val out=executor(d).run(call("phone_type_text","""{"text":"OTP 123456"}"""),Origin.Model(5))
        assertTrue(out.toString(),out is ActionOutcome.Ran);assertEquals(1,p.fake.calls.size)
        repeat(2){model.complete("base",emptyList(),emptyList())}
        assertEquals(1,node.requests.size);assertEquals(2,own.requests.size)
    }
    @Test fun ownAutonomyIsRestoredOnlyAfterPromotion() = runTest {
        val c=HybridCompute(true);val p=Phone(clock,"\u7acb\u5373\u652f\u4ed8")
        val d=toolDispatcher(p,runSpec=spec(),hybridBoundary=HybridScreenBoundary(c){6})
        val ex=executor(d,ApprovalSettings {true})
        d.executeRead(call("phone_observe","{}"))
        assertTrue(ex.run(call(),Origin.Model(6)) is ActionOutcome.Ran)
        assertNotNull(c.upgrade);assertEquals(1,p.fake.calls.size)
    }
    @Test fun familyApprovalSurvivesPromotionAndDenialPreventsExecution() = runTest {
        val c=HybridCompute(true);val p=Phone(clock,"\u53d1\u9001")
        val d=toolDispatcher(p,runSpec=spec(),hybridBoundary=HybridScreenBoundary(c){7})
        var asked=0
        val settings=object : ApprovalSettings {
            override fun autonomous()=true
            override val familyRequired=true
            override suspend fun familyApprove(digest: String, category: String): Boolean { asked++;return false }
        }
        d.executeRead(call("phone_observe","{}"))
        assertFalse(executor(d,settings).run(call(),Origin.Model(7)) is ActionOutcome.Ran)
        assertNotNull(c.upgrade);assertEquals(1,asked);assertTrue(p.fake.calls.isEmpty())
    }
    @Test fun agentLoopRetainsOwnAutonomyAcrossPromotion() = runTest {
        val c = HybridCompute(true)
        val p = Phone(clock, "\u7acb\u5373\u652f\u4ed8")
        val d = toolDispatcher(p, runSpec = spec(), hybridBoundary = HybridScreenBoundary(c) { 2 })
        val node = fakeModel {
            call("phone_observe")
            call("phone_tap", "ref" to "@e1", "snapshotId" to "s1")
            replyTo { throw AssertionError(it.messages.toString()) }
        }
        val nodeModel = object : ChatModel by node {
            override val capabilities = node.capabilities.copy(groundingVerified = true)
        }
        val own = fakeModel { call("phone_observe"); answer("done") }
        val loop = com.wangzan101.mika.core.agent.AgentLoop(HybridModel(nodeModel, own, c) { 2 }, d, "BASE",
            approvals = ApprovalSettings { true }, reviewFinalAnswer = false)
        assertTrue(loop.run("tap the visible button") is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        assertNotNull(c.upgrade)
        assertEquals(1, p.fake.calls.size)
        assertEquals(2, node.requests.size)
        assertEquals(2, own.requests.size)
    }

    @Test fun pureCommunityStillRequiresApprovalForFreeOutwardAction() = runTest {
        val p=Phone(clock,"\u53d1\u9001");val d=toolDispatcher(p,runSpec=spec())
        d.executeRead(call("phone_observe","{}"))
        assertFalse(executor(d,ApprovalSettings {true}).run(call(),Origin.Model(2)) is ActionOutcome.Ran)
        assertTrue(d.community);assertTrue(p.fake.calls.isEmpty())
    }
    @Test fun confirmationSchemaChangesOnceAfterTrustedPromotion() = runTest {
        val compute = HybridCompute(true)
        val boundary = HybridScreenBoundary(compute) { 8 }
        val hybrid = toolDispatcher(Phone(clock), runSpec = spec(), hybridBoundary = boundary)
        val own = toolDispatcher(Phone(clock))
        val pure = toolDispatcher(Phone(clock), runSpec = spec())
        fun confirmation(d: ToolDispatcher) = d.offeredNow(all = true).single { it.name == "request_confirmation" }
        val before = confirmation(hybrid)
        assertEquals(confirmation(pure), before)
        assertTrue(before.parametersJsonSchema.contains("install"))
        boundary.requireOwn(HybridReason.IrreversibleAction)
        val after = confirmation(hybrid)
        assertEquals(confirmation(own), after)
        assertNotEquals(before, after)
        boundary.requireOwn(HybridReason.PasswordField)
        assertEquals(after, confirmation(hybrid))
        assertEquals(before, confirmation(pure))
    }

    @Test fun promotedConfirmationRejectsFreeActionUsingOwnValidation() = runTest {
        val compute = HybridCompute(true)
        val boundary = HybridScreenBoundary(compute) { 9 }
        val hybrid = toolDispatcher(Phone(clock), runSpec = spec(), hybridBoundary = boundary)
        hybrid.offeredNow(all = true)
        boundary.requireOwn(HybridReason.IrreversibleAction)
        val result = hybrid.executeRead(call("request_confirmation",
            """{"action":"send","target":"friend","content":"hello","app":"$clock"}"""), 9)
        assertEquals(JsonPrimitive(false), result.content["ok"])
        assertFalse(result.outcome is Outcome.Confirm)
        assertEquals(HybridUpgrade(9, HybridReason.IrreversibleAction), compute.upgrade)
    }

    @Test fun registryAdmitsPrivateToolsOnlyAfterPromotionAndFreezesEachEpoch() = runTest {
        val compute = HybridCompute(true)
        val boundary = HybridScreenBoundary(compute) { 10 }
        val dispatcher = toolDispatcher(Phone(clock), runSpec = spec(), hybridBoundary = boundary)
        var ready = true
        val privateTool = object : AgentTool {
            override val verb = Verb.RootCommand
            override val label = "private tool"
            override val risk = com.wangzan101.mika.core.tool.Risk.Read
            override val def = ToolDef("test_private", "private test capability")
            override fun available(ctx: ToolContext) = !ctx.config.community && ready
            override suspend fun execute(a: Args, ctx: ToolContext) = ToolExecution(okObj {})
        }
        val registry = ToolRegistry(dispatcher.ctx, listOf(privateTool))
        assertNull(registry["test_private"])
        assertTrue(registry.definitions().isEmpty())
        boundary.requireOwn()
        assertSame(privateTool, registry["test_private"])
        assertEquals(listOf("test_private"), registry.definitions().map { it.name })
        ready = false
        assertSame(privateTool, registry["test_private"])
        assertEquals(listOf("test_private"), registry.definitions().map { it.name })
    }

    @Test fun ownConfigurationIsHiddenUntilPromotionAndRetainsOriginalIdentity() = runTest {
        val compute = HybridCompute(true)
        val boundary = HybridScreenBoundary(compute) { 11 }
        val catalog = com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled()
        val memory = com.wangzan101.mika.core.memory.MemoryStore(object : com.wangzan101.mika.core.memory.MemoryPersistence {
            override fun load() = emptyList<com.wangzan101.mika.core.memory.MemoryItem>()
            override fun save(items: List<com.wangzan101.mika.core.memory.MemoryItem>) = Unit
        }, { 0L }, { "test" })
        fun dispatcher(mode: ComputeMode, boundary: HybridScreenBoundary? = null) = toolDispatcher(
            Phone(clock), visionEnabled = true, recordFrames = true, adaptiveVision = true,
            skills = catalog, memory = memory, runSpec = spec().copy(computeMode = mode), hybridBoundary = boundary)
        val hybrid = dispatcher(ComputeMode.Community, boundary)
        val pure = dispatcher(ComputeMode.Community)
        val own = dispatcher(ComputeMode.Own)
        fun hidden(d: ToolDispatcher) {
            assertNull(d.ctx.config.skills); assertNull(d.ctx.config.memory)
            assertFalse(d.seesImages); assertFalse(d.ctx.config.recordFrames)
            assertFalse(d.ctx.config.adaptiveVision)
        }
        hidden(hybrid); hidden(pure)
        assertSame(catalog, own.ctx.config.skills); assertSame(memory, own.ctx.config.memory)
        assertTrue(own.seesImages); assertTrue(own.ctx.config.recordFrames)
        boundary.requireOwn(HybridReason.PasswordField)
        assertSame(catalog, hybrid.ctx.config.skills); assertSame(memory, hybrid.ctx.config.memory)
        assertTrue(hybrid.seesImages); assertTrue(hybrid.ctx.config.recordFrames)
        assertTrue(hybrid.ctx.config.adaptiveVision)
        hidden(pure)
    }

    @Test fun assembledPrivateCapabilitiesAndGeneralToolsMatchPureEpochs() = runTest {
        val boundary = HybridScreenBoundary(HybridCompute(true)) { 12 }
        var rootCalls = 0
        val root = object : com.wangzan101.mika.core.cap.root.RootPort {
            override suspend fun run(command: String, timeoutMs: Long): com.wangzan101.mika.core.cap.root.RootRun {
                rootCalls++
                return com.wangzan101.mika.core.cap.root.RootRun.Denied
            }
        }
        val catalog = com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled()
        fun dispatcher(mode: ComputeMode, b: HybridScreenBoundary? = null) = toolDispatcher(
            Phone(clock), skills = catalog, root = root, runSpec = spec().copy(computeMode = mode), hybridBoundary = b)
        val hybrid = dispatcher(ComputeMode.Community, boundary)
        val pure = dispatcher(ComputeMode.Community)
        val own = dispatcher(ComputeMode.Own)
        fun definitions(d: ToolDispatcher) = d.offeredNow(all = true)
        fun sections(d: ToolDispatcher) = d.promptSections(definitions(d), all = true)
        assertEquals(definitions(pure), definitions(hybrid))
        assertEquals(sections(pure), sections(hybrid))
        assertFalse(definitions(hybrid).any { it.name == "root_run_command" || it.name == "knowledge_search" })
        val denied = pure.executeRead(call("root_run_command", """{"command":"id"}"""))
        assertEquals(JsonPrimitive("community_root_forbidden"), denied.content["error"])
        assertEquals(0, rootCalls)
        executor(hybrid).run(call("root_run_command", """{"command":"id"}"""), Origin.Model(12))
        assertFalse(boundary.community)
        assertEquals(definitions(own), definitions(hybrid))
        assertEquals(sections(own), sections(hybrid))
        assertTrue(definitions(hybrid).any { it.name == "root_run_command" })
        assertTrue(definitions(hybrid).any { it.name == "knowledge_search" })
        assertFalse(definitions(pure).any { it.name == "root_run_command" })
        assertEquals(1, rootCalls)
    }

}
