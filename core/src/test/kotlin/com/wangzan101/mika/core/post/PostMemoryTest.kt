// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import com.wangzan101.mika.core.memory.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PostMemoryTest {
    @Test fun memoryAndButtonFeedbackReachNextBatchAndRespectEdits() = runBlocking {
        var rows = emptyList<MemoryItem>()
        var id = 0
        val store = MemoryStore(object : MemoryPersistence {
            override fun load() = rows
            override fun save(items: List<MemoryItem>) { rows = items }
        }, { 1000L }, { (++id).toString() })
        val source = MemorySource("manual", atMs = 1000)
        store.add(MemoryKind.fact, "家里有小孩，想要二手婴儿车", emptyList(), source).getOrThrow()
        val procedure = store.add(MemoryKind.procedure, "PRIVATE_PROCEDURE", emptyList(), source).getOrThrow()
        store.approve(procedure.item.id)
        val memory = PostMemory(store)
        val post = Post("p", "12345678", "九成新婴儿车", "", coins = 80, created = 1000, expires = 99999999)
        val engine = PostStandby(save = {})
        engine.enqueue(listOf(post), "mine", 1000)
        engine.review(1000, true, true, memory::preferences, memory::feedback) {
            assertTrue(it.contains("家里有小孩，想要二手婴儿车"))
            assertFalse(it.contains("PRIVATE_PROCEDURE"))
            Json.encodeToString(listOf(PostChoice("p", "tell", "家里需要婴儿车")))
        }
        val suggestion = engine.takeReminder(1000, true)!!.second.single()
        val dislike = memory.record(suggestion, false).getOrThrow().item
        memory.record(suggestion.copy(why = "耐用实惠"), true).getOrThrow()
        assertTrue(rows.any { it.content == "不喜欢这一类：家里需要婴儿车" })
        assertTrue(rows.any { it.content == "喜欢这一类：耐用实惠" })
        assertFalse(memory.preferences().joinToString().contains("不喜欢这一类"))
        engine.enqueue(listOf(post.copy(id = "next")), "mine", 1801000)
        engine.review(1801000, true, true, memory::preferences, memory::feedback) {
            assertTrue(it.contains("不喜欢这一类：家里需要婴儿车"))
            assertTrue(it.contains("喜欢这一类：耐用实惠"))
            Json.encodeToString(listOf(PostChoice("next", "skip", "已经看过")))
        }
        store.forget(dislike.id).getOrThrow()
        assertFalse(memory.feedback().joinToString().contains("不喜欢这一类"))
    }

    @Test fun topicOptOutIsAppliedLocallyAndRespectsDeletion() {
        var rows = emptyList<MemoryItem>()
        val store = MemoryStore(object : MemoryPersistence {
            override fun load() = rows
            override fun save(items: List<MemoryItem>) { rows = items }
        }, { 1000L }, { "opt-out" })
        val item = store.add(MemoryKind.preference,"别管 garden 这类",listOf("garden"),
            MemorySource("manual",atMs = 1000)).getOrThrow().item
        val memory = PostMemory(store)
        val post = Post("p","12345678","Proposal","garden proposal",created = 1000,expires = 99999999,cosign = true)
        assertTrue(memory.skipCommunity(post))
        assertFalse(memory.skipCommunity(post.copy(body = "Other proposal")))
        store.forget(item.id).getOrThrow()
        assertFalse(memory.skipCommunity(post))
    }

    @Test fun unavailableMemoryIsNotSilentlyReplaced() {
        val store = MemoryStore(object : MemoryPersistence {
            override fun load(): List<MemoryItem> = error("locked")
            override fun save(items: List<MemoryItem>) = error("must not write")
        }, { 1000L }, { "id" })
        try {
            PostMemory(store).preferences()
            fail("Expected unavailable memory")
        } catch (expected: IllegalStateException) { assertEquals("post_memory_unavailable", expected.message) }
    }
}
