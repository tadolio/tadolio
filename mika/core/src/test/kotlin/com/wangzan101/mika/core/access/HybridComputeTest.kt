// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HybridComputeTest {
    private fun message(text: String) = Message(Role.User, listOf(Part.Text(text)), Author.User)

    @Test fun requiresOwnModelBeforeCreatingSession() {
        try { HybridCompute(false); fail("must reject") }
        catch (e: IllegalArgumentException) { assertEquals("hybrid_requires_own_model", e.message) }
    }

    @Test fun everyExplicitBoundaryPromotesBeforeNextRequestAndNeverReturns() = runTest {
        for (reason in HybridReason.entries) {
            val node = fakeModel { answer("node") }
            val own = fakeModel { repeat(2) { answer("own") } }
            val compute = HybridCompute(true)
            var step = 1
            val model = HybridModel(node, own, compute) { step }
            model.complete("BASE", listOf(message("weather")), emptyList())
            step = 2
            val event = compute.upgrade(step, reason)
            model.complete("BASE", listOf(message("private page")), emptyList())
            step = 3
            model.complete("BASE", listOf(message("weather again")), emptyList())
            assertSame(event, compute.upgrade(3, HybridReason.Image))
            assertEquals(1, node.requests.size)
            assertEquals(2, own.requests.size)
            assertEquals(HybridUpgrade(2, reason), compute.upgrade)
            assertTrue(compute.usedCommunity)
        }
    }

    @Test fun sensitiveTriggerRequestAndAllFollowingRequestsNeverReachNode() = runTest {
        val payloads = listOf(
            "验证码 123456", "4111 1111 1111 1111", "11010519491231002X",
            "private key: my-private-value", "seed phrase: arbitrary-wallet-words",
            List(12) { "abandon" }.joinToString(" "),
            """{"secret":true,"name":"private-password"}""",
            """{"elements":[{"name":"\u9a8c\u8bc1\u7801"},{"name":"123456"}]}""",
            """{"elements":[{"name":"OTP"},{"name":"654321"}]}""",
        )
        for (text in payloads) {
            val node = fakeModel { answer("node") }
            val own = fakeModel { repeat(2) { answer("own") } }
            val compute = HybridCompute(true)
            val model = HybridModel(node, own, compute) { 4 }
            model.complete("BASE", listOf(message(text)), emptyList())
            model.complete("BASE", listOf(message("ordinary")), emptyList())
            assertEquals(text, 0, node.requests.size)
            assertEquals(2, own.requests.size)
            assertEquals(listOf(message(text)), own.requests.first().messages)
            assertEquals(HybridUpgrade(4, HybridReason.SensitiveContent), compute.upgrade)
            assertFalse(compute.usedCommunity)
            assertFalse(compute.upgrade.toString().contains(text))
        }
    }

    @Test fun remoteTasksStartOwnWithoutNodeAdmission() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { repeat(2) { answer("own") } }
        val compute = HybridCompute(true, remote = true)
        HybridModel(node, own, compute) { 1 }.complete("BASE", listOf(message("weather")), emptyList())
        assertEquals(0, node.requests.size)
        assertEquals(1, own.requests.size)
        assertFalse(compute.usedCommunity)
        assertEquals(HybridUpgrade(1, HybridReason.RemoteTask), compute.upgrade)
    }

    @Test fun auxiliaryModelsShareThePromotion() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { repeat(2) { answer("own") } }
        val compute = HybridCompute(true)
        val main = HybridModel(node, own, compute) { 2 }
        val summary = HybridModel(node, own, compute) { 3 }
        main.complete("BASE", listOf(message("OTP 123456")), emptyList())
        summary.complete("BASE", listOf(message("done")), emptyList())
        assertEquals(0, node.requests.size)
        assertEquals(2, own.requests.size)
    }

    @Test fun frozenAuxiliaryModelsUseOneSessionAndRetainTheirOwnSelections() = runTest {
        val node = fakeModel { repeat(2) { answer("node") } }
        val own = fakeModel { answer("main") }
        val light = fakeModel { answer("light") }
        val vision = fakeModel { answer("vision") }
        val compute = HybridCompute(true)
        var step = 1
        val main = HybridModel(node, own, compute) { step }
        val intent = main.auxiliary(light)
        val verifier = main.auxiliary(vision)
        intent.complete("BASE", listOf(message("weather")), emptyList())
        main.complete("BASE", listOf(message("weather")), emptyList())
        step = 4
        val secret = listOf(message("OTP 123456"))
        intent.complete("BASE", secret, emptyList())
        verifier.complete("BASE", listOf(message("verify")), emptyList())
        main.complete("BASE", listOf(message("done")), emptyList())
        assertEquals(2, node.requests.size)
        assertEquals(secret, light.requests.single().messages)
        assertEquals(1, vision.requests.size)
        assertEquals(1, own.requests.size)
        assertSame(compute, intent.compute)
        assertSame(intent, main.auxiliary(intent))
        assertEquals(HybridUpgrade(4, HybridReason.SensitiveContent), compute.upgrade)
        assertTrue(compute.usedCommunity)
    }

    @Test fun auxiliaryRejectsForeignRoutingStateAndAlreadySanitizedModels() {
        val node = fakeModel { answer("node") }
        val own = fakeModel { answer("own") }
        val main = HybridModel(node, own, HybridCompute(true)) { 1 }
        for (invalid in listOf(CommunityModel(own), HybridModel(node, own, HybridCompute(true)) { 1 })) {
            try { main.auxiliary(invalid); fail("must reject mismatched auxiliary") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(node.requests.isEmpty())
        assertTrue(own.requests.isEmpty())
    }

    @Test fun benignJsonFormattingAndOrdinaryNumbersStayCommunity() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { repeat(2) { answer("own") } }
        val compute = HybridCompute(true)
        val messages = listOf(message("""{ "amount": 123456, "date": "2026-11-05", "phone": "13800138000" }"""))
        HybridModel(node, own, compute) { 1 }.complete("BASE", messages, emptyList())
        val received = node.requests.single().messages.single().parts.single() as Part.Text
        assertEquals(Json.parseToJsonElement((messages.single().parts.single() as Part.Text).text),
            Json.parseToJsonElement(received.text))
        assertEquals(0, own.requests.size)
        assertNull(compute.upgrade)
        assertTrue(compute.usedCommunity)
    }

    @Test fun imagesAndSensitiveSystemPromptNeverReachNode() = runTest {
        for (image in listOf(false, true)) {
            val node = fakeModel { answer("node") }
            val own = fakeModel { repeat(2) { answer("own") } }
            val compute = HybridCompute(true)
            val messages = if (image) listOf(Message(Role.User, listOf(Part.Image("AQID")), Author.User)) else listOf(message("ok"))
            val system = if (image) "BASE" else "OTP 123456"
            HybridModel(node, own, compute) { 1 }.complete(system, messages, emptyList())
            assertEquals(0, node.requests.size)
            assertEquals(messages, own.requests.single().messages)
        }
    }

    @Test fun sensitiveToolIdentifiersNeverReachNode() = runTest {
        for (part in listOf<Part>(
            Part.ToolCall("OTP 123456", "tool", "{}"),
            Part.ToolCall("call", "OTP 123456", "{}"),
            Part.ToolResult("OTP 123456", "ok", false),
        )) {
            val node = fakeModel { answer("node") }
            val own = fakeModel { answer("own") }
            val messages = listOf(Message(Role.Assistant, listOf(part), Author.Mika))
            HybridModel(node, own, HybridCompute(true)) { 2 }.complete("BASE", messages, emptyList())
            assertEquals(0, node.requests.size)
            assertEquals(messages, own.requests.single().messages)
        }
    }

    @Test fun splitVerificationLabelsAndValuesNeverReachNode() = runTest {
        val cases = listOf(
            listOf(Message(Role.User, listOf(Part.Text("验证码"), Part.Text("123456")), Author.User)),
            listOf(message("OTP"), message("654321")),
            listOf(Message(Role.Tool, listOf(Part.ToolResult("a", "验证码", false)), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("b", "123456", false)), Author.Mika)),
        )
        for (messages in cases) {
            val node = fakeModel { answer("node") }
            val own = fakeModel { answer("own") }
            val compute = HybridCompute(true)
            HybridModel(node, own, compute) { 2 }.complete("BASE", messages, emptyList())
            assertEquals(0, node.requests.size)
            assertEquals(messages, own.requests.single().messages)
            assertEquals(HybridUpgrade(2, HybridReason.SensitiveContent), compute.upgrade)
        }
    }

    @Test fun ownFailureCannotFallbackToCommunity() = runTest {
        val node = fakeModel { answer("node") }
        val own = fakeModel { repeat(2) { networkError() } }
        val compute = HybridCompute(true)
        val model = HybridModel(node, own, compute) { 2 }
        repeat(2) {
            try { model.complete("BASE", listOf(message("OTP 123456")), emptyList()); fail("must fail") }
            catch (_: ModelError.Network) { /* propagates without fallback */ }
        }
        assertEquals(0, node.requests.size)
        assertNotNull(compute.upgrade)
    }

    @Test fun everyOutboundPartAndToolDefinitionIsChecked() = runTest {
        val parts = listOf<Part>(
            Part.Text("OTP 123456"),
            Part.ToolResult("call", """{"text":"OTP 123456"}""", false),
            Part.ToolCall("call", "input", """{"text":"OTP 123456"}"""),
            Part.Thinking("OTP 123456"),
        )
        for (part in parts) {
            val node = fakeModel { answer("node") }
            val own = fakeModel { repeat(2) { answer("own") } }
            HybridModel(node, own, HybridCompute(true)) { 2 }.complete(
                "BASE", listOf(Message(Role.User, listOf(part), Author.User)), emptyList())
            assertEquals(part.toString(), 0, node.requests.size)
            assertEquals(1, own.requests.size)
        }
        for (tool in listOf(
            ToolSpec("tool", "OTP 123456", "{}"),
            ToolSpec("tool", "description", """{"example":"OTP 123456"}"""),
        )) {
            val node = fakeModel { answer("node") }
            val own = fakeModel { repeat(2) { answer("own") } }
            HybridModel(node, own, HybridCompute(true)) { 2 }.complete("BASE", emptyList(), listOf(tool))
            assertEquals(0, node.requests.size)
            assertEquals(listOf(tool), own.requests.single().tools)
        }
    }

    @Test fun stripsForeignSignaturesButPreservesOwnThinkingOnSubsequentRequests() = runTest {
        val foreignCall = Part.ToolCall("foreign", "get_time", "{}", "foreign-signature")
        val privateThought = Part.Thinking("own thought", "own-signature")
        val ownCall = Part.ToolCall("own", "get_time", "{}", "own-call-signature")
        val node = fakeModel { answer("node") }
        val own = FakeChatModel("own", Capabilities(true, false, 128_000, false), List(2) { { ModelReply(listOf(privateThought, ownCall), StopReason.ToolUse, null) } })
        val compute = HybridCompute(true)
        val model = HybridModel(node, own, compute) { 2 }
        compute.upgrade(2, HybridReason.AppNotAllowed)
        val history = listOf(Message(Role.Assistant, listOf(Part.Thinking("foreign", "foreign"), foreignCall), Author.Mika))
        val reply = model.complete("BASE", history, emptyList())
        assertEquals(listOf(foreignCall.copy(opaque = null)), own.requests.first().messages.single().parts)
        model.complete("BASE", history + Message(Role.Assistant, reply.parts, Author.Mika), emptyList())
        assertEquals(listOf(privateThought, ownCall), own.requests.last().messages.last().parts)
        assertEquals(0, node.requests.size)
    }

    @Test fun upgradeWaitsForAdmittedCallThenBlocksConcurrentAuxiliarySend() = runTest {
        val compute = HybridCompute(true)
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var nodeCalls = 0
        var ownCalls = 0
        val first = async {
            compute.route(1, null, community = {
                nodeCalls++; admitted.complete(Unit); release.await()
            }, own = { ownCalls++ })
        }
        admitted.await()
        val promotion = async { compute.upgrade(2, HybridReason.WindowHidden) }
        runCurrent()
        val auxiliary = async { compute.route(2, null, community = { nodeCalls++ }, own = { ownCalls++ }) }
        runCurrent()
        assertFalse(promotion.isCompleted)
        release.complete(Unit)
        first.await(); promotion.await(); auxiliary.await()
        assertEquals(1, nodeCalls)
        assertEquals(1, ownCalls)
        assertTrue(compute.usedCommunity)
    }

    @Test fun cancelledCallReleasesAdmissionButDoesNotEraseCommunityAccounting() = runTest {
        val compute = HybridCompute(true)
        val admitted = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val call = async { compute.route(1, null, community = { admitted.complete(Unit); blocked.await() }, own = {}) }
        admitted.await()
        call.cancelAndJoin()
        compute.upgrade(2, HybridReason.AppNotAllowed)
        assertTrue(compute.usedCommunity)
        assertEquals(HybridUpgrade(2, HybridReason.AppNotAllowed), compute.upgrade)
    }
}
