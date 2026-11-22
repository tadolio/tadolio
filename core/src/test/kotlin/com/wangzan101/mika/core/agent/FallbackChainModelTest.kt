// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FallbackChainModelTest {
    private val reply = ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, null)
    private fun fake(name: String, call: () -> ModelReply) = object : ChatModel {
        override val name = name
        override val capabilities = Capabilities(true, false, 32000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = call()
    }

    @Test fun exhaustedKeysAdvanceThroughTheFrozenChainAndStayOnTheWorkingModel() = runTest {
        val calls = mutableListOf<String>()
        val switches = mutableListOf<Int>()
        val model = FallbackChainModel(listOf(
            fake("first") { calls += "first"; throw ModelError.Auth() },
            fake("second") { calls += "second"; throw ModelError.QuotaExceeded() },
            fake("third") { calls += "third"; reply },
        ), listOf("a", "b", "c"), switches::add)
        repeat(2) { assertEquals(reply, model.complete("", emptyList(), emptyList())) }
        assertEquals(listOf("first", "second", "third", "third"), calls)
        assertEquals(listOf(1, 2), switches)
        assertEquals("third", model.name)
    }

    @Test fun contentAndTaskBudgetErrorsDoNotSpendAnotherProvidersTokens() = runTest {
        for (failure in listOf(ModelError.ContentRefused(), ModelError.TaskBudgetExceeded(100, 100))) {
            var calls = 0
            val model = FallbackChainModel(listOf(fake("first") { throw failure }, fake("second") { calls++; reply }), listOf("a", "b"))
            try { model.complete("", emptyList(), emptyList()); fail() }
            catch (actual: ModelError) { assertSame(failure, actual) }
            assertEquals(0, calls)
        }
    }
}
