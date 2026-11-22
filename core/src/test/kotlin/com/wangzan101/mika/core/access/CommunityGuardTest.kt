// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.testing.*
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Community mode informs once at the start and otherwise behaves exactly like the user's own model. */
class CommunityGuardTest {
    @Test fun communityPromptDescribesVisibilityAndKeepsItsSnapshot() {
        val tools = toolDispatcher(FakeScreen(), inlineScreen = true, runSpec = spec())
        tools.expand("phone")
        val specs = tools.offeredNow(all = true)
        val prompt = com.wangzan101.mika.core.agent.SystemPrompt.assemble(
            com.wangzan101.mika.core.agent.SystemPrompt.identity(), tools.promptSections(specs, all = true))
        assertTrue(prompt.contains("can see the task and screen"))
        assertFalse(prompt.contains("Privacy apps"))
        PromptSurface.check("community-phone", PromptSurface.system(prompt, specs))
    }
    private val bank = "com.icbc"
    private val chat = "com.tencent.mm"
    private fun spec() = RunSpec(RunId("test"), "test", "test", computeMode = ComputeMode.Community)
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c", name, args)

    @Test fun bankAndChatOpenAndReadWithImagesAndNoExtraNotice() = runTest {
        val fake = FakeScreen()
        val phone = object : ScreenPort by fake {
            override suspend fun snapshot() = fake.snapshot().copy(packageName = bank)
            override suspend fun peek() = fake.peek().copy(packageName = bank)
        }
        val tools = toolDispatcher(phone, visionEnabled = true, recordFrames = true, runSpec = spec())
        for (pkg in listOf(bank, chat)) {
            val opened = tools.execute(call("phone_launch_app", """{"package":"$pkg"}"""))
            assertEquals(true, opened.content["ok"]?.jsonPrimitive?.boolean)
            assertFalse(opened.content.toString().contains("miner"))
        }
        assertEquals(2, fake.calls.count { it.startsWith("open ") })
        val read = tools.executeRead(call("phone_observe"))
        assertTrue(read.content.toString().contains("搜索"))
        // Vision follows the selected model capability in either compute mode.
        assertTrue(tools.seesImages)
        assertTrue(read.images.isNotEmpty())
    }

    @Test fun unknownForegroundAndManualFinanceClassificationAllowOpening() = runTest {
        val fake = FakeScreen()
        val raw = object : ScreenPort by fake {
            override suspend fun foregroundPackages(): List<String?> = listOf(null)
            override val appAccess = AppAccessPolicy {
                AppAccessState(classifications = mapOf(bank to AppClassification(AppCategory.Finance, manual = true)))
            }
        }
        val tools = toolDispatcher(raw, runSpec = spec())
        assertEquals(true, tools.execute(call("phone_launch_app", """{"package":"$bank"}""")).content["ok"]?.jsonPrimitive?.boolean)
        assertEquals(1, fake.calls.size)
    }

    @Test fun manualBlockRemainsEffectiveInBothModes() = runTest {
        for (mode in ComputeMode.entries) {
            val fake = FakeScreen()
            val access = AppAccessScreenPort(fake, AppAccessPolicy { AppAccessState(blocked = setOf(bank)) }, { listOf("com.x") })
            val tools = toolDispatcher(access, runSpec = spec().copy(computeMode = mode))
            try {
                tools.execute(call("phone_launch_app", """{"package":"$bank"}"""))
                fail("manual block must apply")
            } catch (error: AppAccessDenied) {
                assertEquals("app_blocked", error.code)
            }
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test fun outboundScreenImagesRemainAlongsideRedactedText() {
        val image = Part.Image("AQID")
        val message = Message(Role.Tool, listOf(image, Part.Text("OTP 123456")), Author.Mika)
        val sent = CommunityMessages {}.outgoing(listOf(message)).single()
        assertSame(image, sent.parts.filterIsInstance<Part.Image>().single())
        assertFalse(sent.parts.filterIsInstance<Part.Text>().single().text.contains("123456"))
    }

    @Test fun noPerAppNoticeMachineryRemains() {
        val root = listOf(java.io.File("src/main"), java.io.File("core/src/main")).first { it.isDirectory }
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("CommunityPrivacy") || it.readText().contains("CommunityNoticeScreenPort") }.toList()
        assertTrue(hits.toString(), hits.isEmpty())
    }
}
