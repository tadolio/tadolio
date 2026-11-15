// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.i18n.UserText
import com.wangzan101.mika.i18n.*
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.displayLabel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HivoDefaultsI18nTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun pointsAndChatShareVisibleActivitiesAcrossSparseModelTurns() {
        fun activity(step: Int, kind: String, target: String? = null, failed: Boolean = false) =
            RunEvent.Activity(0, step, kind, "tap", target, failed = failed)
        val acts = listOf(
            activity(1, "observe"), // Successful read with no target is invisible.
            activity(2, "observe", "com.example.clock"),
            activity(3, "observe", "com.example.clock"), // Same place: merged.
            activity(7, "action", "com.example.clock"),
            activity(7, "action", "com.example.clock"), // Batch activities count separately.
        )
        for (source in listOf(ResFileTexts.chinese, ResFileTexts.english, ResFileTexts.traditional)) {
            UiText.source = source
            val reportRows = activityStepRows(acts, { "Tap" }, { "Clock" })
            val ex = Exchange("", emptyList(), ExchangeStatus.Done("", null), live = false, activities = acts, notes = mapOf(7 to "note"))
            assertEquals(3, reportRows.size)
            assertEquals(reportRows.size, ChatViewModel.rows(ex, { "Tap" }, { "Clock" }).size)
            assertEquals(1, activityStepRows(listOf(activity(9, "observe", failed = true)), { "Tap" }, { it }).size)
            assertTrue(activityStepRows(emptyList(), { it }, { it }).isEmpty())
        }
    }

    @Test fun providerDisplayOrderAndNamesKeepStablePresetKeys() {
        val original = ProviderPresets.all
        assertEquals(original, ProviderPresets.forDisplay(true))
        val ordered = ProviderPresets.forDisplay(false)
        assertEquals(listOf("OpenAI", "Anthropic", "Gemini", "OpenRouter", "xAI", "DeepSeek", "Mistral", "Groq", "Ollama", "LM Studio"), ordered.take(10).map { it.label })
        assertEquals(original.toSet(), ordered.toSet())
        val qwen = original.first { it.label == "通义千问" }
        assertEquals("通义千问", qwen.displayLabel())
        UiText.source = ResFileTexts.english
        assertEquals("Qwen", qwen.displayLabel())
        assertEquals("Google Gemini", ordered[2].displayLabel())
        assertEquals("通义千问", qwen.label)
    }

    @Test fun coreUserCopyFollowsAppResourcesAndExplainsStepLimit() {
        val previous = UserText.source
        try {
            CoreUserTexts.install()
            UiText.source = ResFileTexts.english
            assertEquals("Checking the result", userCopy("正在核实结果"))
            assertEquals("Verification timed out", userCopy("核查超时"))
            assertEquals("正在核实结果", com.wangzan101.mika.core.agent.AgentLoop.VERIFYING_NOTE)
            assertTrue(UserText.get("hivo_core_0", 40).contains("Settings › Task running › Step limit"))
            assertTrue(UserText.get("hivo_core_29", "xAI", "xAI").contains("Sign in to xAI"))
            val error = com.wangzan101.mika.core.model.ModelError.BadResponse("流式响应不是合法 JSON")
            assertTrue(com.wangzan101.mika.core.i18n.modelUserMessage(error).contains("Streaming response is not valid JSON"))
            assertEquals("模型返回了无法解析的内容: 流式响应不是合法 JSON", error.message)
            UiText.source = ResFileTexts.chinese
            assertTrue(UserText.get("hivo_core_0", 40).contains("设置 › 任务运行 › 步数上限"))
            assertEquals("通义千问", ProviderPresets.all.first { it.label == "通义千问" }.displayLabel())
            // Every core key must be routed, or the app throws instead of showing the message.
            val coreKeys = kotlinx.serialization.json.Json.parseToJsonElement(
                checkNotNull(UserText::class.java.getResourceAsStream("/i18n/user-zh.json")).bufferedReader(Charsets.UTF_8).use { it.readText() }
            ).let { (it as kotlinx.serialization.json.JsonObject).keys - "_notice" }
            for (key in coreKeys) runCatching { UserText.get(key, "x", "x") }.onFailure { if (it is NoSuchElementException) throw AssertionError("core key not routed: $key", it) }
            assertEquals("你的服务商现在不在线", UserText.get("phone_operator_offline"))
            UiText.source = ResFileTexts.english
            assertEquals("Your provider has used today’s allowance. It resets tomorrow.", UserText.get("hivo_operator_daily_limit"))
        } finally {
            UserText.source = previous
        }
    }
}
