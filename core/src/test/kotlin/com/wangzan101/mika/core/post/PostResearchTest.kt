// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class PostResearchTest {
    private fun model(reply: (List<Message>, List<ToolSpec>) -> List<Part>) = object : ChatModel {
        override val capabilities = Capabilities(false, false, 32000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
            ModelReply(reply(messages, tools), StopReason.EndTurn, null)
    }
    private val prompt = "Post body. Sources: https://www.sec.gov/filing.htm, https://example.org/news."

    @Test fun onlyCitedLinksOpenAndEveryAttemptCountsThenFinalJsonIsForced() = runBlocking {
        val opened = mutableListOf<String>()
        var rounds = 0
        val research = PostResearch { opened += it; "untrusted page" }
        val result = research.complete(model { _, tools ->
            rounds++
            if (rounds == 4) {
                assertTrue(tools.isEmpty())
                listOf(Part.Text("[]"))
            } else {
                if (rounds == 1) assertEquals(listOf("open_post_source"), tools.map { it.name })
                if (rounds > 1) assertTrue(tools.isEmpty())
                listOf(
                    Part.ToolCall("s$rounds", "web_search", """{"query":"recent event"}"""),
                    Part.ToolCall("x$rounds", "open_post_source", """{"url":"https://elsewhere.test/"}"""),
                    Part.ToolCall("a$rounds", "open_post_source", """{"url":"https://www.sec.gov/filing.htm"}"""),
                    Part.ToolCall("b$rounds", "open_post_source", """{"url":"https://example.org/news"}"""))
            }
        }, "system", prompt)
        assertEquals("[]", result)
        // Search is not offered; the uncited link counts against the budget but is never fetched.
        assertEquals(listOf("https://www.sec.gov/filing.htm", "https://example.org/news"), opened)
        assertEquals(4, rounds)
    }

    @Test fun postsWithoutLinksOfferNoTools() = runBlocking {
        val research = PostResearch { error("must not read") }
        assertEquals("[]", research.complete(model { _, tools -> assertTrue(tools.isEmpty()); listOf(Part.Text("[]")) }, "s", "no links here"))
    }

    @Test fun premiseIsGivenOnBothRoutesWithDatesAndUnreadableSourceStillDecides() = runBlocking {
        for (community in listOf(false, true)) {
            val now = 1800028800000L
            val post = Post("p", "12345678", "title", "new public event https://example.org/news", created = now - 86400000,
                expires = now + 86400000, cosign = true)
            val engine = PostStandby(save = {}, zone = ZoneId.of("Asia/Shanghai"))
            engine.enqueue(listOf(post), "mine", now)
            var calls = 0
            var decided = ""
            val research = PostResearch { error("offline") }
            engine.review(now, true, !community, { listOf("PRIVATE") }, { emptyList() },
                cosign = { choice, _ -> decided = choice.action }, communityRoute = community) { prompt ->
                assertTrue(prompt.contains("Current local date: 2027-01-16"))
                assertTrue(prompt.contains("Published: 2027-01-15"))
                assertTrue(prompt.contains("Take the events a cosign post describes as its premise"))
                assertFalse(prompt.contains("web_search"))
                if (community) assertFalse(prompt.contains("PRIVATE"))
                research.complete(model { messages, _ ->
                    calls++
                    if (calls == 1) listOf(Part.ToolCall("r", "open_post_source", """{"url":"https://example.org/news"}"""))
                    else {
                        val result = messages.last().parts.single() as Part.ToolResult
                        assertTrue(result.isError)
                        assertTrue(result.content.contains("untrusted"))
                        listOf(Part.Text("```json\n[{\"id\":\"p\",\"action\":\"sign\",\"why\":\"I support the request\"}]\n```"))
                    }
                }, "system", prompt)
            }
            assertEquals("sign", decided)
            assertEquals(2, calls)
        }
    }

    @Test fun cancellationPropagates() = runBlocking {
        val research = PostResearch { throw CancellationException("cancelled") }
        try {
            research.complete(model { _, _ -> listOf(Part.ToolCall("r", "open_post_source", """{"url":"https://example.org/news"}""")) }, "system", prompt)
            fail("cancelled read must propagate")
        } catch (_: CancellationException) { }
    }
}
