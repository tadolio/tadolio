// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KeyAvailabilityTest {
    private val reply = ModelReply(listOf(Part.Text("synthetic reply")), StopReason.EndTurn, null)
    private fun fake(call: () -> ModelReply) = object : ChatModel {
        override val capabilities = Capabilities(true, true, 32000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = call()
    }

    @Test fun classifiesStatusAndBodyWithoutRotatingServerOrContentErrors() {
        assertEquals(KeyFailure.invalid, keyFailure(401, ""))
        assertEquals(KeyFailure.invalid, keyFailure(403, ""))
        assertEquals(KeyFailure.invalid, keyFailure(400, "INVALID API KEY"))
        assertEquals(KeyFailure.exhausted, keyFailure(402, ""))
        assertEquals(KeyFailure.exhausted, keyFailure(400, "insufficient_quota"))
        assertEquals(KeyFailure.exhausted, keyFailure(400, "余额不足"))
        assertEquals(KeyFailure.rate_limited, keyFailure(429, ""))
        assertEquals(KeyFailure.other, keyFailure(503, "insufficient_quota invalid api key"))
        assertEquals(KeyFailure.other, keyFailure(400, "content_filter"))
        assertTrue(ModelHttp.openAiError(503, null, "insufficient_quota", "m") is ModelError.Server)
        assertTrue(ModelHttp.geminiError(503, null, "insufficient_quota", "m") is ModelError.Server)
        assertTrue(ModelHttp.openAiError(400, null, "余额不足", "m") is ModelError.QuotaExceeded)
    }

    @Test fun cooldownAndNextLocalDatePersistAndEditingClearsFailure() {
        var time = 0L
        var date = "2026-11-16"
        var saved = emptyMap<String, KeyAvailability.State>()
        val health = KeyAvailability({ time }, { date }, changed = { saved = it })
        health.fail("one", KeyFailure.rate_limited)
        time = 59_999
        assertEquals(KeyFailure.rate_limited, health.status("one"))
        time++
        assertEquals(KeyFailure.other, health.status("one"))
        health.fail("one", KeyFailure.invalid)
        health.fail("two", KeyFailure.exhausted)
        val restored = KeyAvailability({ time }, { date }, saved)
        assertEquals(KeyFailure.invalid, restored.status("one"))
        restored.reset(listOf("one"))
        assertEquals(KeyFailure.other, restored.status("one"))
        assertEquals(KeyFailure.exhausted, restored.status("two"))
        date = "2026-11-17"
        assertEquals(KeyFailure.other, restored.status("two"))
    }

    @Test fun triesEveryKeyAtMostOnceInOrderAndNotifiesFallbackOnce() = runTest {
        val attempts = mutableListOf<Int>()
        var notices = 0
        val model = KeyFailoverModel(listOf(
            "one" to fake { attempts += 1; throw ModelError.Auth() },
            "two" to fake { attempts += 2; throw ModelError.QuotaExceeded() },
            "three" to fake { attempts += 3; throw ModelError.RateLimited(null) },
        ), KeyAvailability(), fake { attempts += 4; reply }, { notices++ })
        assertEquals(reply, model.complete("", emptyList(), emptyList()))
        assertEquals(reply, model.complete("", emptyList(), emptyList()))
        assertEquals(listOf(1, 2, 3, 4, 4), attempts)
        assertEquals(1, notices)
    }

    @Test fun networkServerAndContentFailuresKeepTheirExistingHandling() = runTest {
        for (error in listOf(ModelError.Network(), ModelError.Server(500), ModelError.ContentRefused(), ModelError.ContextTooLong())) {
            var backupCalls = 0
            val model = KeyFailoverModel(listOf("one" to fake { throw error }), KeyAvailability(), fake { backupCalls++; reply })
            try { model.complete("", emptyList(), emptyList()); fail() } catch (actual: ModelError) { assertSame(error, actual) }
            assertEquals(0, backupCalls)
        }
    }

    @Test fun pooledRequestsRotateHealthyKeysAndSkipPersistedFailures() = runTest {
        val attempts = mutableListOf<String>()
        val health = KeyAvailability()
        val model = KeyFailoverModel(listOf(
            "one" to fake { attempts += "one"; reply },
            "two" to fake { attempts += "two"; reply },
            "three" to fake { attempts += "three"; reply },
        ), health, rotate = true)
        repeat(3) { model.complete("", emptyList(), emptyList()) }
        assertEquals(listOf("one", "two", "three"), attempts)
        health.fail("one", KeyFailure.invalid)
        model.complete("", emptyList(), emptyList())
        assertEquals(listOf("one", "two", "three", "two"), attempts)
    }

    @Test fun preservesStreamingOnEachAttempt() = runTest {
        var streamingCalls = 0
        val stream = object : StreamingChatModel {
            override val capabilities = Capabilities(true, true, 32000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("streaming required")
            override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
                streamingCalls++; onText("synthetic reply"); return reply
            }
        }
        val model = KeyFailoverModel(listOf("one" to fake { throw ModelError.Auth() }, "two" to stream), KeyAvailability())
        var text = ""
        model.completeStreaming("", emptyList(), emptyList()) { text = it }
        assertEquals("synthetic reply", text)
        assertEquals(1, streamingCalls)
    }
}
