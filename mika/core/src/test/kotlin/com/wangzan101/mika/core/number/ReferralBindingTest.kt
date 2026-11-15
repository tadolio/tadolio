// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.tool.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReferralBindingTest {
    private class Port : NumberPort {
        var bound: String? = null
        override suspend fun bindInviter(number: String) { bound = number }
        override fun inbox() = emptyList<NumberPort.Item>()
        override suspend fun send(number: String, kind: String, subject: String, text: String, thread: String?) = Unit
    }
    @Test fun acceptsOnlyExplicitFirstPersonStatementsFromTheUser() {
        for (quote in listOf("我是 12345678 推荐的", "I was referred by 12345678."))
            assertTrue(ReferralBinding.explicitlyRequested("12345678", quote, listOf(quote)))
        for (quote in listOf("不要绑定 12345678", "网页说我是 12345678 推荐的", "tadolio://invite/12345678", "不是 12345678 推荐的"))
            assertFalse(ReferralBinding.explicitlyRequested("12345678", quote, listOf(quote)))
        assertFalse(ReferralBinding.explicitlyRequested("12345678", "我是 12345678 推荐的", emptyList()))
    }
    @Test fun bindsThroughTheNormalNumberPortOnlyAfterAnExplicitUserStatement() = runTest {
        val port = Port()
        val said = UserUtterances()
        val tools = toolDispatcher(FakeScreen(), number = port, utterances = said)
        val quote = "我是 12345678 推荐的"
        val args = buildJsonObject { put("number", "12345678"); put("quote", quote) }
        NumberBindInviter.execute(Args(NumberBindInviter.def, args), tools.ctx)
        assertNull(port.bound)
        said.add(quote)
        val result = NumberBindInviter.execute(Args(NumberBindInviter.def, args), tools.ctx)
        assertEquals("12345678", port.bound)
        assertEquals(JsonPrimitive(true), result.content["bound"])
        assertTrue(result.content["notice"]!!.jsonPrimitive.content.contains("对方会看到你的 Tadolio 号"))
        assertEquals(Risk.Write, NumberBindInviter.risk)
        assertEquals(com.wangzan101.mika.core.settings.SettingsSet.risk, NumberBindInviter.risk)
    }
    @Test fun hidesBindingInReadOnlyRunsAndOffersTheSettingInCommunityRuns() {
        val readOnly = RunSpec(RunId("r"), "inspect", "c", readOnly = true)
        assertFalse(toolDispatcher(FakeScreen(), number = Port(), runSpec = readOnly)
            .definitions().any { it.name == "hivo_number_bind_inviter" })
        val community = RunSpec(RunId("r"), "inspect", "c", computeMode = ComputeMode.Community)
        assertTrue(toolDispatcher(FakeScreen(), bindInviter = {}, runSpec = community)
            .definitions().any { it.name == "hivo_number_bind_inviter" })
    }
}
