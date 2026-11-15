// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.task

import com.wangzan101.mika.core.access.HybridCompute
import com.wangzan101.mika.core.access.HybridModel
import com.wangzan101.mika.core.access.HybridReason
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.settings.ProviderModels
import com.wangzan101.mika.family.ElderModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskModelsTest {
    private class Fake(private val reply: String = "ok", private val fail: Boolean = false) : ChatModel {
        override val capabilities = Capabilities(true, true, 32000, false)
        val requests = mutableListOf<List<Message>>()
        val systems = mutableListOf<String>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            requests += messages
            systems += system
            check(!fail) { "simulated model failure" }
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
    }
    private fun message(text: String) = listOf(Message(Role.User, listOf(Part.Text(text)), Author.User))

    @Test fun ownSelectionsKeepIdentityAndSameModelSkipsOnlyStepRouting() {
        val main = Fake()
        val light = Fake()
        val vision = Fake()
        val models = TaskModels(main, light, vision, distinctLight = false, community = false)
        assertSame(main, models.main)
        assertSame(light, models.light)
        assertSame(vision, models.vision)
        assertSame(light, models.auxiliary(light))
        assertNull(models.routedLight)
        assertNull(models.auxiliary(null))
        assertSame(light, TaskModels(main, light, null, true, false).routedLight)
    }

    @Test fun pureCommunityAuxiliaryStillRedactsAndHasNoStepRouter() = runTest {
        val main = Fake()
        val light = Fake()
        val models = TaskModels(main, light, null, distinctLight = true, community = true)
        assertSame(light, models.light)
        assertNull(models.routedLight)
        models.auxiliary(light)!!.complete("BASE", message("验证码 123456"), emptyList())
        assertEquals(1, light.requests.size)
        assertFalse(light.requests.single().toString().contains("123456"))
    }

    @Test fun summaryOutcomeAndVisionSharePromotionAndFrozenOwnSelections() = runTest {
        val node = Fake()
        val own = Fake()
        val light = Fake()
        val vision = Fake()
        val compute = HybridCompute(true)
        val main = HybridModel(node, own, compute) { 3 }
        val models = TaskModels(main, light, vision, distinctLight = true, community = true)
        assertSame(compute, (models.light as HybridModel).compute)
        assertSame(models.light, models.routedLight)
        assertSame(models.light, models.auxiliary(models.light))
        main.complete("BASE", message("weather"), emptyList())
        models.auxiliary(models.light)!!.complete("BASE", message("OTP 123456"), emptyList())
        models.auxiliary(models.light)!!.complete("BASE", message("done"), emptyList())
        models.vision!!.complete("BASE", message("evidence"), emptyList())
        main.complete("BASE", message("finish"), emptyList())
        assertEquals(1, node.requests.size)
        assertEquals(2, light.requests.size)
        assertEquals(1, vision.requests.size)
        assertEquals(1, own.requests.size)
        assertEquals(HybridReason.SensitiveContent, compute.upgrade?.reason)
        assertEquals(3, compute.upgrade?.step)
        assertTrue(compute.usedCommunity)
    }

    @Test fun remoteSummaryStartsOwnAndMissingLightFallsBackToFrozenMain() = runTest {
        val node = Fake()
        val own = Fake()
        val compute = HybridCompute(true, remote = true)
        val main = HybridModel(node, own, compute) { 1 }
        val models = TaskModels(main, null, null, false, true)
        models.auxiliary(models.light ?: models.main)!!.complete("BASE", message("stopped"), emptyList())
        assertEquals(0, node.requests.size)
        assertEquals(1, own.requests.size)
        assertFalse(compute.usedCommunity)
    }

    @Test fun foreignRunAuxiliaryIsRejectedAtAssembly() {
        val node = Fake()
        val own = Fake()
        val main = HybridModel(node, own, HybridCompute(true)) { 1 }
        val foreign = HybridModel(node, own, HybridCompute(true)) { 1 }
        assertThrows(IllegalArgumentException::class.java) { TaskModels(main, foreign, null, true, true) }
    }

    @Test fun stopAndOutcomeFallbackUseOwnAfterSensitiveWriterFailure() = runTest {
        val node = Fake()
        val own = Fake("""{"outcome":"done","reason":"completed"}""")
        val light = Fake(fail = true)
        val compute = HybridCompute(true)
        val models = TaskModels(HybridModel(node, own, compute) { 6 }, light, null, true, true)
        val writer = models.auxiliary(models.light)
        val fallback = models.auxiliary(models.main)
        val summary = StopSummary(writer, fallback).summarize("weather", emptyList(), "OTP 123456")
        assertTrue(summary.contains("completed"))
        val outcome = OutcomeJudge(writer!!, fallback).judge("weather", "done")
        assertEquals(RunOutcome.Done, outcome.outcome)
        assertEquals(0, node.requests.size)
        assertEquals(2, light.requests.size)
        assertEquals(2, own.requests.size)
        assertEquals(6, compute.upgrade?.step)
        assertFalse(compute.usedCommunity)
    }

    @Test fun stoppedBeforeSelectionHasARecordOnlySummary() = runTest {
        val summary = StopSummary(null, null).summarize("weather", emptyList(), "working")
        assertTrue(summary.isNotBlank())
    }

    @Test fun frozenOwnAssemblyPreservesMainDecorationAndAuxiliaryIdentity() {
        val own = Fake()
        val light = Fake()
        val vision = Fake()
        val models = TaskModels.fromFrozen(ProviderModels(null, own, light, vision, true), { it })!!
        assertSame(own, models.main)
        assertSame(light, models.light)
        assertSame(vision, models.vision)
        assertSame(light, models.routedLight)
        assertNull(models.hybridBoundary)
        assertNull(TaskModels.fromFrozen(ProviderModels(null, null, light, vision, true), { it }))
    }

    @Test fun frozenHybridDecoratesBothMainTransportsAndSharesTheScreenBoundary() = runTest {
        val node = Fake()
        val own = Fake()
        val light = Fake()
        val vision = Fake()
        var step = 1
        val models = TaskModels.fromFrozen(ProviderModels(null, own, light, vision, true),
            { ElderModel(it, "ELDER") }, node, step = { step })!!
        val main = models.main as HybridModel
        models.main.complete("BASE", message("weather"), emptyList())
        assertEquals(listOf("BASE\n\nELDER"), node.systems)
        step = 4
        models.hybridBoundary!!.beforeTarget("com.tencent.mm")
        models.main.complete("BASE", message("continue"), emptyList())
        models.light!!.complete("LIGHT", message("summary"), emptyList())
        models.vision!!.complete("VISION", message("evidence"), emptyList())
        assertEquals(listOf("BASE\n\nELDER"), own.systems)
        assertEquals(listOf("LIGHT"), light.systems)
        assertEquals(listOf("VISION"), vision.systems)
        assertEquals(1, node.requests.size)
        assertEquals(4, main.compute.upgrade!!.step)
        assertEquals(HybridReason.AppNotAllowed, main.compute.upgrade!!.reason)
        assertFalse(models.hybridBoundary.community)
        assertSame(main.compute, (models.light as HybridModel).compute)
        assertSame(main.compute, (models.vision as HybridModel).compute)
    }

    @Test fun frozenRemoteAssemblyNeverAdmitsCommunityAndSameMainSkipsRouting() = runTest {
        val node = Fake()
        val own = Fake()
        val models = TaskModels.fromFrozen(ProviderModels(null, own, own, null, false),
            { it }, node, remote = true)!!
        models.main.complete("BASE", message("weather"), emptyList())
        models.auxiliary(models.light)!!.complete("BASE", message("summary"), emptyList())
        assertNull(models.routedLight)
        assertEquals(0, node.requests.size)
        assertEquals(2, own.requests.size)
        assertEquals(HybridReason.RemoteTask, (models.main as HybridModel).compute.upgrade!!.reason)
        assertFalse(models.main.compute.usedCommunity)
        assertFalse(models.hybridBoundary!!.community)
    }

    @Test fun frozenAssemblyRejectsForeignOwnRolesBeforeDecoration() {
        val own = Fake()
        val foreign = HybridModel(Fake(), own, HybridCompute(true)) { 1 }
        var decorations = 0
        assertThrows(IllegalArgumentException::class.java) {
            TaskModels.fromFrozen(ProviderModels(null, own, foreign, null, true),
                { decorations++; ElderModel(it, "ELDER") }, Fake())
        }
        assertEquals(0, decorations)
        assertThrows(IllegalArgumentException::class.java) {
            TaskModels.fromFrozen(ProviderModels(null, foreign, null, null, false), { it }, Fake())
        }
    }
    @Test fun frozenAssemblyUsesLiveUserPrivacyClassificationBeforeSendingToNode() = runTest {
        val node = Fake()
        val own = Fake()
        val pkg = "user.private.app"
        var state = com.wangzan101.mika.core.access.AppAccessState()
        val models = TaskModels.fromFrozen(ProviderModels(null, own, null, null, false),
            { it }, node, appAccess = com.wangzan101.mika.core.access.AppAccessPolicy { state })!!
        models.hybridBoundary!!.beforeTarget(pkg)
        models.main.complete("BASE", message("public task"), emptyList())
        assertTrue(models.hybridBoundary.community)
        state = state.copy(classifications = mapOf(pkg to com.wangzan101.mika.core.access.AppClassification(
            com.wangzan101.mika.core.access.AppCategory.Social, manual = true)))
        models.hybridBoundary.beforeTarget(pkg)
        models.main.complete("BASE", message("private task"), emptyList())
        assertFalse(models.hybridBoundary.community)
        assertEquals(1, node.requests.size)
        assertEquals(1, own.requests.size)
        assertFalse(node.requests.single().toString().contains("private task"))
    }

}
