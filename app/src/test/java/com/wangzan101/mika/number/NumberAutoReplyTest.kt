// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NumberAutoReplyTest {
    private var ledger: String? = null
    private val engine = NumberAutoReply({ ledger }, { ledger = it })
    private val incoming = NumberEnvelope("ask", "Repair", "Can you help?", "thread")
    private val reply = """{"action":"reply","text":"What needs repair?","note":"Clarifying the job."}"""

    @Test fun excludedMessagesNeverCallModel() = runBlocking {
        for (scenario in listOf("blocked", "disabled", "taken", "spam", "ack", "expired")) {
            val envelope = when (scenario) {
                "ack" -> incoming.copy(kind = "ack")
                "expired" -> incoming.copy(expires = 1000)
                else -> incoming
            }
            val result = engine.respond("", listOf(false to envelope),
                if (scenario == "spam") "spam" else "normal",
                scenario == "blocked", scenario != "disabled", scenario == "taken",
                1000, 0, null) { error("must not call") }
            assertNull(scenario, result)
        }
        assertNull(ledger)
    }

    @Test fun promptOnlyReceivesPublicCardTransactionAndTime() = runBlocking {
        val result = engine.respond("Public plumber", listOf(false to incoming), "normal",
            false, true, false, 1000, 0, null) { prompt ->
            assertTrue(prompt.contains("Public plumber"))
            assertTrue(prompt.contains(com.wangzan101.mika.core.observation.Untrusted.wrap(
                "${incoming.kind}: ${incoming.subject}\n${incoming.text}")))
            assertTrue(prompt.contains("Current time:"))
            for (forbidden in listOf("memory", "contacts", "tools", "screen", "phone tool"))
                assertFalse(forbidden, prompt.contains(forbidden, ignoreCase = true))
            reply
        }
        assertEquals("reply", result?.action)
    }

    @Test fun allLimitsHandoffWithoutModelCall() = runBlocking {
        val own = incoming.copy(kind = "reply", auto = true)
        val four = List(4) { true to own } + (false to incoming)
        val six = List(3) { listOf(true to own, false to own) }.flatten()
        for ((history, today, last) in listOf(
            Triple(four, 0, null), Triple(six, 0, null),
            Triple(listOf(false to incoming), 10, null),
            Triple(listOf(false to incoming), 0, 999L),
        )) {
            assertEquals("handoff", engine.respond("", history, "normal", false, true, false,
                1000, today, last) { error("must not call") }?.action)
        }
        assertNull(ledger)
    }

    @Test fun dailyModelQuotaPersistsSeparatelyAndResetsNextDay() = runBlocking {
        repeat(50) {
            engine.respond("", listOf(false to incoming), "urgent", false, true, false,
                1000, 0, null) { throw IllegalStateException() }
        }
        val restarted = NumberAutoReply({ ledger }, { ledger = it })
        assertEquals("handoff", restarted.respond("", listOf(false to incoming), "normal",
            false, true, false, 1000, 0, null) { error("quota exceeded") }?.action)
        assertEquals("reply", restarted.respond("", listOf(false to incoming), "normal",
            false, true, false, 86401000, 0, null) { reply }?.action)
    }

    @Test fun onlyFreshMessagesAreAnswered() {
        val now = 10L * 24 * 60 * 60 * 1000
        assertEquals(false, NumberAutoReply.stale(now - 60_000, now))
        assertEquals(true, NumberAutoReply.stale(now - NumberAutoReply.MAX_AGE_MS - 1, now))
        assertEquals(true, NumberAutoReply.stale(null, now))
    }

    @Test fun validatesActionsAndReplyLength() {
        for (action in listOf("handoff", "close")) {
            assertEquals(action, NumberAutoReply.parse("""{"action":"$action","text":"","note":"Done"}""")?.action)
        }
        assertNull(NumberAutoReply.parse(reply.replace("reply", "send")))
        assertNull(NumberAutoReply.parse(reply.replace("What needs repair?", "x".repeat(1001))))
        assertNull(NumberAutoReply.parse("not json"))
        // Handoff without text (or with null) keeps the owner's note; a reply still needs text.
        assertEquals("Ask owner", NumberAutoReply.parse("""```json
{"action":"handoff","note":"Ask owner"}
```""")?.note)
        assertEquals("handoff", NumberAutoReply.parse("""{"action":"handoff","text":null,"note":"n"}""")?.action)
        assertNull(NumberAutoReply.parse("""{"action":"reply","note":"n"}"""))
    }

    @Test fun minuteBoundaryAllowsAnotherReply() = runBlocking {
        assertEquals("reply", engine.respond("", listOf(false to incoming), "normal",
            false, true, false, 61000, 9, 1000) { reply }?.action)
    }

    @Test fun twoLocalStoresClarifyThenHandoff() = runBlocking {
        var aData: String? = null
        var bData: String? = null
        val a = NumberStore({ aData }, { aData = it })
        val b = NumberStore({ bData }, { bData = it })
        var otherLedger: String? = null
        val otherEngine = NumberAutoReply({ otherLedger }, { otherLedger = it })
        var calls = 0
        fun deliver(id: String, from: String, to: String, envelope: NumberEnvelope) {
            val body = kotlinx.serialization.json.JsonObject(envelope.encode() +
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(id))
                    put("from", kotlinx.serialization.json.JsonPrimitive(from))
                    put("to", kotlinx.serialization.json.JsonPrimitive(to))
                    put("time", kotlinx.serialization.json.JsonPrimitive(1000L + calls * 60000L))
                })
            a.add(body, from == "12345678")
            b.add(body, from == "87654321")
        }
        fun history(store: NumberStore) = store.messages().map {
            (it.getValue("outgoing").toString() == "true") to NumberEnvelope.decode(it)
        }
        deliver("initial", "12345678", "87654321", incoming)
        assertFalse(a.takenOver("87654321", incoming.thread))
        assertTrue(b.claimAutoReply("initial"))
        val question = engine.respond("Plumber", history(b), "normal", false, true, false,
            1000, 0, null) { calls++; reply }!!
        b.recordAutoReply("initial", question)
        deliver("question", "87654321", "12345678", incoming.copy(
            kind = "reply", text = question.text, auto = true))
        assertTrue(a.claimAutoReply("question"))
        val answer = otherEngine.respond("Needs sink repair", history(a), "normal", false, true,
            false, 61000, 0, null) {
            calls++
            """{"action":"reply","text":"The sink leaks.","note":"Explained the issue."}"""
        }!!
        a.recordAutoReply("question", answer)
        deliver("answer", "12345678", "87654321", incoming.copy(
            kind = "reply", text = answer.text, auto = true))
        assertTrue(b.claimAutoReply("answer"))
        val finish = engine.respond("Plumber", history(b), "normal", false, true, false,
            121000, 1, 1000) {
            calls++
            """{"action":"handoff","text":"","note":"Owner must arrange the visit."}"""
        }!!
        assertEquals("handoff", finish.action)
        b.recordAutoReply("answer", finish)
        assertEquals("pending", b.transactions().single().state)
        assertEquals("Owner must arrange the visit.", b.transactions().single().autoNote)
        assertFalse(NumberStore({ bData }, { bData = it }).claimAutoReply("answer"))
        assertEquals(3, calls)
        assertEquals(3, a.messages().size)
        assertEquals(3, b.messages().size)
        assertEquals(2, history(b).count { it.second.auto })
    }
}
