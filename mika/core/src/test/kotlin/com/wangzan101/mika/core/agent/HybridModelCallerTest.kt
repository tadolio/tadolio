// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.access.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.testing.*
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.*
import org.junit.Test

class HybridModelCallerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun rateLimitWaitAccountingPreservesCommunityRedactionAndOwnHistory() = runTest {
        for (mode in ComputeMode.entries) {
            val model = FakeModel(ModelError.RateLimited(1500), answerReply("done"), answerReply("next"))
            val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(
                RunId("retry"), "retry", "retry", computeMode = mode))
            val caller = ModelCaller(model, tools, null, SpeedFlags.OFF, RetryPolicy(), {}, { testScheduler.currentTime })
            caller.reset("BASE")
            val history = listOf(message("验证码 123456"))
            caller.ask(history, emptyList(), 1)
            assertEquals(1500L, caller.lastRetryWaitMs)
            assertEquals(2, model.requests.size)
            for (request in model.requests) {
                assertEquals(mode == ComputeMode.Own, request.toString().contains("123456"))
            }
            assertTrue(history.toString().contains("123456"))
            caller.ask(history, emptyList(), 2)
            assertEquals(0L, caller.lastRetryWaitMs)
        }
    }

    private fun message(text: String) = Message(Role.User, listOf(Part.Text(text)), Author.User)
    private fun caller(model: ChatModel, community: Boolean = true, emit: (RunEvent) -> Unit = {}): ModelCaller {
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(
            RunId("test"), "test", "test", computeMode = if (community) ComputeMode.Community else ComputeMode.Own))
        return ModelCaller(model, tools, null, SpeedFlags.OFF, RetryPolicy.NONE, emit, { 0L }).also { it.reset("BASE") }
    }

    @Test fun rawSensitiveHistoryPromotesBeforeCallerSanitizationAndDoesNotMutateHistory() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { repeat(2) { answer("own") } }
        val compute = HybridCompute(true)
        var step = 1
        val caller = caller(HybridModel(node, own, compute) { step })
        caller.ask(listOf(message("weather")), emptyList(), step)
        step = 2
        val raw = listOf(message("\u9a8c\u8bc1\u7801 123456"))
        caller.ask(raw, emptyList(), step)
        step = 3
        caller.ask(listOf(message("weather again")), emptyList(), step)
        assertEquals(1, node.requests.size)
        assertEquals(2, own.requests.size)
        assertEquals(raw, own.requests.first().messages)
        assertEquals("\u9a8c\u8bc1\u7801 123456", (raw.single().parts.single() as Part.Text).text)
        assertEquals(HybridUpgrade(2, HybridReason.SensitiveContent), compute.upgrade)
    }

    @Test fun rawImagePromotesBeforeCompressorDropsUnsupportedImages() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val compute = HybridCompute(true)
        val raw = listOf(Message(Role.User, listOf(Part.Text("look"), Part.Image("AA==")), Author.User))
        caller(HybridModel(node, own, compute) { 7 }).ask(raw, emptyList(), 7)
        assertEquals(0, node.requests.size)
        assertEquals(1, own.requests.size)
        assertEquals(HybridUpgrade(7, HybridReason.Image), compute.upgrade)
        assertEquals(raw, own.requests.single().messages)
    }

    @Test fun promotionRebuildsHistoryInsteadOfReusingCommunityCompaction() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val nodeModel = object : ChatModel by node {
            override val capabilities = node.capabilities.copy(contextTokens = 100000, compactionTriggerTokens = 1000)
        }
        val ownModel = object : ChatModel by own {
            override val capabilities = own.capabilities.copy(contextTokens = 100000, compactionTriggerTokens = 90000)
        }
        val compute = HybridCompute(true)
        val hybrid = HybridModel(nodeModel, ownModel, compute) { 2 }
        val caller = caller(hybrid)
        val raw = listOf(message("weather")) + (1..30).flatMap { n -> listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("c$n", "phone_observe", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c$n", "OLD_RAW_SCREEN_$n " + "safe weather ".repeat(500), false)), Author.Mika)) }
        caller.ask(raw, emptyList(), 1)
        assertFalse(node.requests.single().messages.toString().contains("OLD_RAW_SCREEN_1 "))
        compute.upgrade(2, HybridReason.AppNotAllowed)
        caller.ask(raw, emptyList(), 2)
        assertTrue(own.requests.single().messages.toString().contains("OLD_RAW_SCREEN_1 "))
        assertEquals(1, node.requests.size)
    }

    @Test fun promotionAfterCompactionRebuildsBeforeEitherTransport() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val nodeModel = object : ChatModel by node {
            override val capabilities = node.capabilities.copy(contextTokens = 100000, compactionTriggerTokens = 1000)
        }
        val ownModel = object : ChatModel by own {
            override val capabilities = own.capabilities.copy(contextTokens = 100000, compactionTriggerTokens = 90000)
        }
        val compute = HybridCompute(true)
        val caller = caller(HybridModel(nodeModel, ownModel, compute) { 2 }, emit = { event ->
            if (event is RunEvent.Compressed) launch(start = CoroutineStart.UNDISPATCHED) {
                compute.upgrade(2, HybridReason.AppNotAllowed)
            }
        })
        val raw = listOf(message("weather")) + (1..30).flatMap { n -> listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("c$n", "phone_observe", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c$n", "OLD_RAW_SCREEN_$n " + "safe weather ".repeat(500), false)), Author.Mika)) }
        val ownSpec = ToolSpec("own_only", "own", "{}")
        var refreshes = 0
        caller.ask(raw, emptyList(), 2, promotedOffer = {
            refreshes++
            caller.systemPrompt = "OWN_AFTER_RACE"
            listOf(ownSpec)
        })
        assertEquals(1, refreshes)
        assertEquals("OWN_AFTER_RACE", own.requests.single().system)
        assertEquals(listOf(ownSpec), own.requests.single().tools)
        assertNotNull(compute.upgrade)
        assertFalse(compute.usedCommunity)
        assertTrue(node.requests.isEmpty())
        assertEquals(1, own.requests.size)
        assertTrue(own.requests.single().messages.toString().contains("OLD_RAW_SCREEN_1 "))
    }

    @Test fun promotionRebuildsPromptAndDeclarationsBeforeTransport() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { repeat(2) { answer("own") } }
        val compute = HybridCompute(true)
        val caller = caller(HybridModel(node, own, compute) { 2 })
        val communitySpec = ToolSpec("safe", "community", "{}")
        val ownSpec = ToolSpec("private", "own", "{}")
        var refreshes = 0
        val refresh = {
            refreshes++
            assertNotNull(compute.upgrade)
            caller.systemPrompt = "OWN_SECTIONS"
            listOf(ownSpec)
        }
        caller.ask(listOf(message("weather")), listOf(communitySpec), 1, promotedOffer = refresh)
        assertEquals(0, refreshes)
        caller.ask(listOf(message("verification code 123456")), listOf(communitySpec), 2, promotedOffer = refresh)
        caller.ask(listOf(message("continue")), listOf(communitySpec), 3, promotedOffer = refresh)
        assertEquals(1, node.requests.size)
        assertEquals("BASE", node.requests.single().system)
        assertEquals(listOf(communitySpec), node.requests.single().tools)
        assertEquals(2, refreshes)
        own.requests.forEach {
            assertEquals("OWN_SECTIONS", it.system)
            assertEquals(listOf(ownSpec), it.tools)
        }
    }

    @Test fun promotionKeepsFinalTurnToolRestriction() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val compute = HybridCompute(true)
        val caller = caller(HybridModel(node, own, compute) { 1 })
        val forbidden = ToolSpec("private_write", "own", "{}")
        caller.ask(listOf(message("verification code 123456")), emptyList(), 1,
            promotedOffer = { TurnPolicy.finalTools(listOf(forbidden)) { com.wangzan101.mika.core.tool.Verb.TypeText } })
        assertTrue(node.requests.isEmpty())
        assertTrue(own.requests.single().tools.isEmpty())
    }

    @Test fun agentLoopRebuildsOfferAfterRawHistoryPromotes() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val compute = HybridCompute(true)
        val boundary = HybridScreenBoundary(compute) { 1 }
        var rootCalls = 0
        val root = object : com.wangzan101.mika.core.cap.root.RootPort {
            override suspend fun run(command: String, timeoutMs: Long): com.wangzan101.mika.core.cap.root.RootRun {
                rootCalls++
                return com.wangzan101.mika.core.cap.root.RootRun.Denied
            }
        }
        val tools = toolDispatcher(FakeScreen(), root = root, hybridBoundary = boundary,
            runSpec = RunSpec(RunId("hybrid-loop"), "test", "test", computeMode = ComputeMode.Community))
        val loop = AgentLoop(HybridModel(node, own, compute) { 1 }, tools, "BASE", reviewFinalAnswer = false)
        assertTrue(loop.run("verification code 123456") is TaskState.Succeeded)
        assertTrue(node.requests.isEmpty())
        val request = own.requests.single()
        assertTrue(request.tools.any { it.name == "root_run_command" })
        assertEquals(SystemPrompt.assemble("BASE", tools.promptSections(request.tools)), request.system)
        assertTrue(request.messages.toString().contains("123456"))
        assertEquals(0, rootCalls)
    }

    @Test fun agentLoopRestoresFrozenOwnBasePromptAfterActionPromotion() = runTest {
        val node = fakeModel { call("phone_type_text", "text" to "verification code 123456") }
        val own = fakeModel { call("phone_observe"); answer("done") }
        val compute = HybridCompute(true)
        val screen = FakeScreen()
        val tools = toolDispatcher(screen, hybridBoundary = HybridScreenBoundary(compute) { 1 },
            runSpec = RunSpec(RunId("hybrid-prompt"), "test", "test", computeMode = ComputeMode.Community))
        val loop = AgentLoop(HybridModel(node, own, compute) { 1 }, tools, "COMMUNITY_BASE",
            hybridOwnBasePrompt = "FROZEN_OWN_PRIVATE_BASE", reviewFinalAnswer = false)
        assertTrue(loop.run("type the supplied code") is TaskState.Succeeded)
        assertEquals(1, node.requests.size)
        assertFalse(node.requests.single().system.contains("FROZEN_OWN_PRIVATE_BASE"))
        assertEquals(2, own.requests.size)
        assertTrue(own.requests.first().system.contains("FROZEN_OWN_PRIVATE_BASE"))
        assertFalse(own.requests.first().system.contains("COMMUNITY_BASE"))
        assertEquals(1, screen.calls.size)
    }

    @Test fun rawHistoryPromotionSelectsOwnBaseBeforeFirstTransport() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val compute = HybridCompute(true)
        val tools = toolDispatcher(FakeScreen(), hybridBoundary = HybridScreenBoundary(compute) { 1 },
            runSpec = RunSpec(RunId("hybrid-base"), "test", "test", computeMode = ComputeMode.Community))
        val loop = AgentLoop(HybridModel(node, own, compute) { 1 }, tools, "COMMUNITY_BASE",
            hybridOwnBasePrompt = "FROZEN_OWN_PRIVATE_BASE", reviewFinalAnswer = false)
        assertTrue(loop.run("verification code 123456") is TaskState.Succeeded)
        assertTrue(node.requests.isEmpty())
        assertTrue(own.requests.single().system.contains("FROZEN_OWN_PRIVATE_BASE"))
        assertFalse(own.requests.single().system.contains("COMMUNITY_BASE"))
    }

    @Test fun privateConversationRestoredOnceOnlyAfterActionPromotion() = runTest {
        val node = fakeModel { call("phone_type_text", "text" to "verification code 123456") }
        val own = fakeModel { call("phone_observe"); answer("done") }
        val compute = HybridCompute(true)
        val screen = FakeScreen()
        val tools = toolDispatcher(screen, hybridBoundary = HybridScreenBoundary(compute) { 1 },
            runSpec = RunSpec(RunId("hybrid-private"), "test", "test", computeMode = ComputeMode.Community))
        val profile = com.wangzan101.mika.core.memory.ProfileEntry("p", "PRIVATE_PROFILE_MARKER", 1,
            com.wangzan101.mika.core.memory.ProfileStatus.active)
        val loop = AgentLoop(HybridModel(node, own, compute) { 1 }, tools, "BASE",
            profileSnapshot = listOf(profile), reviewFinalAnswer = false)
        assertTrue(loop.run("type the supplied code", listOf(Turn("PRIVATE_PRIOR_MARKER", "old answer", "succeeded"))) is TaskState.Succeeded)
        assertEquals(1, node.requests.size)
        assertFalse(node.requests.single().messages.toString().contains("PRIVATE_"))
        assertEquals(2, own.requests.size)
        own.requests.forEach { request ->
            val text = request.messages.toString()
            assertEquals(1, Regex("PRIVATE_PRIOR_MARKER").findAll(text).count())
            assertEquals(1, Regex("PRIVATE_PROFILE_MARKER").findAll(text).count())
            assertTrue(text.contains("123456"))
        }
        assertEquals(1, screen.calls.size)
    }

    @Test fun rawPreflightRestoresPriorTurnsOnlyToOwn() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val compute = HybridCompute(true)
        val tools = toolDispatcher(FakeScreen(), hybridBoundary = HybridScreenBoundary(compute) { 1 },
            runSpec = RunSpec(RunId("hybrid-prior-preflight"), "test", "test", computeMode = ComputeMode.Community))
        val loop = AgentLoop(HybridModel(node, own, compute) { 1 }, tools, "BASE", reviewFinalAnswer = false)
        assertTrue(loop.run("verification code 123456", listOf(Turn("PRIVATE_HISTORY", "answer", "succeeded"))) is TaskState.Succeeded)
        assertTrue(node.requests.isEmpty())
        assertTrue(own.requests.single().messages.toString().contains("PRIVATE_HISTORY"))
    }

    @Test fun pureCommunityNeverRestoresPrivatePriorTurns() = runTest {
        val node = fakeModel { answer("weather") }
        val tools = toolDispatcher(FakeScreen(),
            runSpec = RunSpec(RunId("community-prior"), "test", "test", computeMode = ComputeMode.Community))
        val loop = AgentLoop(node, tools, "BASE", reviewFinalAnswer = false)
        assertTrue(loop.run("weather", listOf(Turn("PRIVATE_HISTORY", "answer", "succeeded"))) is TaskState.Succeeded)
        assertFalse(node.requests.single().messages.toString().contains("PRIVATE_HISTORY"))
    }

    @Test fun privateMemoryIsReadAndMarkedOnlyAfterTrustedPromotion() = runTest {
        val item = com.wangzan101.mika.core.memory.MemoryItem("private-memory",
            com.wangzan101.mika.core.memory.MemoryKind.fact, "PRIVATE_MEMORY_MARKER", emptyList(),
            com.wangzan101.mika.core.memory.MemorySource("manual", atMs = 0),
            com.wangzan101.mika.core.memory.MemoryStatus.active, createdAt = 0, updatedAt = 0)
        var saves = 0
        val store = com.wangzan101.mika.core.memory.MemoryStore(
            object : com.wangzan101.mika.core.memory.MemoryPersistence {
                override fun load() = listOf(item)
                override fun save(items: List<com.wangzan101.mika.core.memory.MemoryItem>) { saves++ }
            }, { 10L }, { "unused" })
        val node = fakeModel { call("phone_type_text", "text" to "verification code 123456") }
        val own = fakeModel { call("phone_observe"); answer("done") }
        val compute = HybridCompute(true)
        val tools = toolDispatcher(FakeScreen(), hybridBoundary = HybridScreenBoundary(compute) { 1 },
            runSpec = RunSpec(RunId("hybrid-memory"), "test", "test", computeMode = ComputeMode.Community))
        val knowledge = com.wangzan101.mika.core.knowledge.KnowledgeStore(listOf(
            com.wangzan101.mika.core.knowledge.KnowledgeEntry("supplied", "supplied",
                com.wangzan101.mika.core.knowledge.Surface.Android, emptyList(), emptyList(), emptyList(),
                null, "private knowledge", "PRIVATE_KNOWLEDGE_MARKER")))
        val skills = com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled()
        val loop = AgentLoop(HybridModel(node, own, compute) { 1 }, tools, "BASE",
            memory = store, knowledge = knowledge, skills = skills, reviewFinalAnswer = false)
        assertTrue(loop.run("type the supplied code") is TaskState.Succeeded)
        assertEquals(1, node.requests.size)
        val nodeText = node.requests.single().messages.toString()
        assertFalse(nodeText.contains("PRIVATE_MEMORY_MARKER"))
        assertFalse(nodeText.contains("PRIVATE_KNOWLEDGE_MARKER"))
        assertFalse(nodeText.contains("skills_index"))
        assertEquals(2, own.requests.size)
        own.requests.forEach {
            assertEquals(1, Regex("PRIVATE_MEMORY_MARKER").findAll(it.messages.toString()).count())
            assertEquals(1, Regex("PRIVATE_KNOWLEDGE_MARKER").findAll(it.messages.toString()).count())
            assertTrue(it.messages.toString().contains("skills_index"))
        }
        assertEquals(listOf(item.id), loop.usedMemoryIds)
        assertEquals(1, store.items.value.single().useCount)
        assertEquals(1, saves)
    }

    @Test fun privateKnowledgeStaysHiddenUntilPromotionAndPureModesKeepTheirContext() = runTest {
        val knowledge = com.wangzan101.mika.core.knowledge.KnowledgeStore(listOf(
            com.wangzan101.mika.core.knowledge.KnowledgeEntry("weather", "weather",
                com.wangzan101.mika.core.knowledge.Surface.Android, emptyList(), emptyList(), emptyList(),
                null, "private knowledge", "PRIVATE_KNOWLEDGE_MARKER")))
        val skills = com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled()
        for (mode in listOf("hybrid", "community", "own")) {
            val transport = fakeModel { answer("done") }
            val own = fakeModel { answer("own") }
            val compute = HybridCompute(true)
            val hybrid = mode == "hybrid"
            val tools = toolDispatcher(FakeScreen(),
                hybridBoundary = if (hybrid) HybridScreenBoundary(compute) { 1 } else null,
                runSpec = RunSpec(RunId(mode), "test", "test",
                    computeMode = if (mode == "own") ComputeMode.Own else ComputeMode.Community))
            val model = if (hybrid) HybridModel(transport, own, compute) { 1 } else transport
            assertTrue(AgentLoop(model, tools, "BASE", knowledge = knowledge, skills = skills,
                reviewFinalAnswer = false).run("weather") is TaskState.Succeeded)
            val text = transport.requests.single().messages.toString()
            assertEquals(mode == "own", text.contains("PRIVATE_KNOWLEDGE_MARKER"))
            assertEquals(mode == "own", text.contains("skills_index"))
            assertTrue(own.requests.isEmpty())
            assertNull(compute.upgrade)
        }
    }

    @Test fun privateChannelFactsAreReadOnceOnlyAfterPromotion() = runTest {
        val compute = HybridCompute(true)
        var reads = 0
        val node = fakeModel { call("phone_type_text", "text" to "verification code 123456") }
        val own = fakeModel { call("phone_observe"); answer("done") }
        val tools = toolDispatcher(FakeScreen(), hybridBoundary = HybridScreenBoundary(compute) { 1 },
            runSpec = RunSpec(RunId("hybrid-channel"), "test", "test", computeMode = ComputeMode.Community))
        val knowledge = com.wangzan101.mika.core.knowledge.KnowledgeStore(listOf(
            com.wangzan101.mika.core.knowledge.KnowledgeEntry("weather", "weather",
                com.wangzan101.mika.core.knowledge.Surface.Android, listOf("private.channel.app"),
                emptyList(), emptyList(), null, "private channel", "PRIVATE_CHANNEL_GUIDE")))
        val loop = AgentLoop(HybridModel(node, own, compute) { 1 }, tools, "BASE",
            knowledge = knowledge, channelFacts = {
                assertNotNull(compute.upgrade)
                reads++
                com.wangzan101.mika.core.knowledge.ChannelFacts(setOf("private.channel.app"), emptySet(), false)
            }, reviewFinalAnswer = false)
        assertTrue(loop.run("weather") is TaskState.Succeeded)
        assertEquals(1, reads)
        assertEquals(1, node.requests.size)
        assertFalse(node.requests.single().messages.toString().contains("private.channel.app"))
        assertEquals(2, own.requests.size)
        own.requests.forEach {
            assertEquals(1, Regex("Available channels:").findAll(it.messages.toString()).count())
            assertTrue(it.messages.toString().contains("app: installed (private.channel.app)"))
        }
    }

    @Test fun speedSettingsFollowTrustedPromotionAndPureModesStayFixed() = runTest {
        val configured = SpeedFlags(replay = true, lightSteps = true, adaptiveVision = true,
            structuredCompaction = true, deepLinks = true)
        for (mode in listOf("hybrid", "community", "own")) {
            val compute = HybridCompute(true)
            val node = fakeModel { answer("done") }
            val own = fakeModel { answer("own") }
            val tools = toolDispatcher(FakeScreen(),
                hybridBoundary = if (mode == "hybrid") HybridScreenBoundary(compute) { 1 } else null,
                runSpec = RunSpec(RunId(mode), "test", "test",
                    computeMode = if (mode == "own") ComputeMode.Own else ComputeMode.Community))
            val model = if (mode == "hybrid") HybridModel(node, own, compute) { 1 } else node
            val loop = AgentLoop(model, tools, "BASE", flags = configured, reviewFinalAnswer = false)
            val caller = ModelCaller(model, tools, ModelRouter(model, own), configured,
                RetryPolicy.NONE, {}, { 0L })
            assertEquals(mode == "own", loop.flags.replay)
            assertEquals(mode == "own", loop.flags.lightSteps)
            assertEquals(mode == "own", loop.flags.adaptiveVision)
            assertEquals(mode == "own", caller.recordRoles)
            compute.upgrade(1, HybridReason.AppNotAllowed)
            val activeOwn = mode != "community"
            assertEquals(activeOwn, loop.flags.replay)
            assertEquals(activeOwn, loop.flags.lightSteps)
            assertEquals(activeOwn, loop.flags.adaptiveVision)
            assertEquals(activeOwn, caller.recordRoles)
            assertTrue(loop.flags.structuredCompaction)
            assertTrue(loop.flags.deepLinks)
            assertTrue(loop.run("weather") is TaskState.Succeeded)
            assertEquals(activeOwn, tools.captureTargets)
            if (mode == "hybrid") {
                assertTrue(node.requests.isEmpty())
                assertEquals(1, own.requests.size)
            }
        }
    }

    @Test fun attachedScreenPromotesBeforePrivateSourceRead() = runTest {
        for (mode in listOf("hybrid", "community", "own")) {
            val node = fakeModel { answer("done") }
            val own = fakeModel { answer("own") }
            val compute = HybridCompute(true)
            var reads = 0
            val source = object : com.wangzan101.mika.core.userscreen.UserScreenSource {
                override suspend fun screen(id: String): com.wangzan101.mika.core.userscreen.UserScreen {
                    if (mode == "hybrid") assertNotNull(compute.upgrade)
                    reads++
                    return com.wangzan101.mika.core.userscreen.UserScreen(id, 0, "private.app", "Private",
                        lines = listOf(com.wangzan101.mika.core.userscreen.ScreenLine("PRIVATE_SCREEN_MARKER", 1, 1)))
                }
                override suspend fun image(id: String): ByteArray? = null
            }
            val hybrid = mode == "hybrid"
            val tools = toolDispatcher(FakeScreen(),
                hybridBoundary = if (hybrid) HybridScreenBoundary(compute) { 1 } else null,
                runSpec = RunSpec(RunId(mode), "test", "test",
                    computeMode = if (mode == "own") ComputeMode.Own else ComputeMode.Community))
            val model = if (hybrid) HybridModel(node, own, compute) { 1 } else node
            val loop = AgentLoop(model, tools, "BASE", userScreens = source, reviewFinalAnswer = false)
            assertTrue(loop.run("[[screen:private]]\nDescribe this screen") is TaskState.Succeeded)
            assertEquals(if (mode == "community") 0 else 1, reads)
            if (hybrid) {
                assertTrue(node.requests.isEmpty())
                assertTrue(own.requests.single().messages.toString().contains("PRIVATE_SCREEN_MARKER"))
            } else {
                assertTrue(own.requests.isEmpty())
                assertEquals(mode == "own", node.requests.single().messages.toString().contains("PRIVATE_SCREEN_MARKER"))
            }
        }
    }

    @Test fun retainedLightRouterUsesFrozenOwnModelAfterSharedPromotion() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("main") }
        val light = fakeModel { answer("light") }
        val compute = HybridCompute(true)
        val main = HybridModel(node, own, compute) { 2 }
        val router = ModelRouter(own, light).hybrid(main)
        val picked = router.pick(StepContext(2, true, false, true, false, true))
        assertTrue(router.isLight(picked))
        picked.complete("BASE", listOf(message("weather")), emptyList())
        compute.upgrade(2, HybridReason.AppNotAllowed)
        picked.complete("BASE", listOf(message("private")), emptyList())
        assertEquals(1, node.requests.size)
        assertEquals(1, light.requests.size)
        assertTrue(own.requests.isEmpty())
    }

    @Test fun intentFallbackAndStopSummaryShareIrreversibleAuxiliaryAdmission() = runTest {
        val node = fakeModel { answer("READ") }
        val own = fakeModel { answer("ACT"); answer("Stopped safely") }
        val light = FakeModel(answerReply("invalid intent"), IllegalStateException("summary writer unavailable"))
        val compute = HybridCompute(true)
        val main = HybridModel(node, own, compute) { 3 }
        val gate = IntentGate(main.auxiliary(light), main)
        assertEquals(IntentGate.Intent.Read, gate.assess("weather", emptyList()))
        assertTrue(light.requests.isEmpty())
        assertEquals(IntentGate.Intent.Act, gate.assess("OTP 123456", emptyList()))
        assertTrue(light.requests.single().toString().contains("123456"))
        val result = StopSummary(main.auxiliary(light), main).summarize("weather", emptyList(), "draft")
        assertEquals("Stopped safely", result)
        assertEquals(1, node.requests.size)
        assertEquals(2, own.requests.size)
        assertEquals(HybridUpgrade(3, HybridReason.SensitiveContent), compute.upgrade)
    }

    @Test fun unwrappedAuxiliaryIsRejectedBeforeTransport() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val unsafe = fakeModel { answer("unsafe") }
        val compute = HybridCompute(true)
        try {
            caller(HybridModel(node, own, compute) { 1 }).ask(listOf(message("\u9a8c\u8bc1\u7801 123456")), emptyList(), 1, using = unsafe)
            fail("unwrapped auxiliary must fail closed")
        } catch (e: IllegalArgumentException) {
            assertEquals("hybrid_requires_shared_model_state", e.message)
        }
        assertTrue(unsafe.requests.isEmpty())
        assertTrue(node.requests.isEmpty())
    }

    @Test fun pureCommunityStillRedactsAndPureOwnPreservesRawContent() = runTest {
        val raw = listOf(message("\u9a8c\u8bc1\u7801 123456"))
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        caller(node).ask(raw, emptyList(), 1)
        caller(own, community = false).ask(raw, emptyList(), 1)
        assertFalse(node.requests.single().messages.toString().contains("123456"))
        assertEquals(raw, own.requests.single().messages)
    }
}
