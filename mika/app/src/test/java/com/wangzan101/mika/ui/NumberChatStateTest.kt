// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NumberChatStateTest {
    private fun message(id: String, peer: String, time: Long, outgoing: Boolean = false, text: String = "hello") = buildJsonObject {
        put("id", id); put("from", if (outgoing) "87654321" else peer)
        put("to", if (outgoing) peer else "87654321"); put("time", time)
        put("outgoing", outgoing); put("text", text)
    }
    @Test fun conversationsSortByLatestMessageAndPreviewBothDirections() {
        val messages = listOf(message("a", "11111111", 30), message("b", "22222222", 20),
            message("c", "22222222", 40, true, " first\n second "))
        val summaries = NumberChatState.summaries(messages, emptySet())
        assertEquals(listOf("22222222", "11111111"), summaries.map { it.number })
        assertEquals("first second", summaries.first().preview)
        assertEquals(40L, summaries.first().time)
        assertTrue(NumberChatState.summaries(emptyList(), emptySet()).isEmpty())
    }
    @Test fun unreadIncrementsAndEnteringConversationClearsOnlyThatPeer() {
        val first = listOf(message("a", "11111111", 30), message("b", "22222222", 20))
        var seen = NumberChatState.read(first, "11111111", emptySet())
        assertEquals(0, NumberChatState.summaries(first, seen).first().unread)
        assertEquals(1, NumberChatState.summaries(first, seen).last().unread)
        val next = first + message("c", "11111111", 40) + message("d", "11111111", 50, true)
        assertEquals(1, NumberChatState.summaries(next, seen).first().unread)
        seen = NumberChatState.read(next, "11111111", seen)
        assertEquals(0, NumberChatState.summaries(next, seen).first().unread)
        assertEquals(seen, NumberChatState.read(next, "11111111", seen))
    }
    @Test fun durableOutboxIdentitySurvivesAutomaticFlushAndRepeatedText() {
        val old = message("old", "11111111", 10, true)
        val draft = message("pending", "11111111", 20, true)
        assertFalse(NumberChatState.delivered(draft, listOf(old)))
        assertFalse(NumberChatState.delivered(draft, listOf(message("different", "11111111", 30, true))))
        assertTrue(NumberChatState.delivered(draft, listOf(old, draft)))
        val beforeLookup = JsonObject(draft - "id" + ("before" to JsonArray(listOf(JsonPrimitive("old")))))
        assertFalse(NumberChatState.delivered(beforeLookup, listOf(old)))
        assertTrue(NumberChatState.delivered(beforeLookup, listOf(old, draft)))
    }
    @Test fun sameTextInAnotherTransactionDoesNotCompleteDraft() {
        val sent = message("sent", "11111111", 30, true)
        val draft = JsonObject(sent - "id" + ("thread" to JsonPrimitive("first")))
        val other = JsonObject(sent + ("thread" to JsonPrimitive("other")))
        assertFalse(NumberChatState.delivered(draft, listOf(other)))
        assertTrue(NumberChatState.delivered(draft, listOf(JsonObject(sent + ("thread" to JsonPrimitive("first"))))))
    }
    @Test fun separatorsRequireMoreThanFiveMinutes() {
        assertTrue(NumberChatState.separator(null, 0))
        assertFalse(NumberChatState.separator(0, 300000))
        assertTrue(NumberChatState.separator(0, 300001))
        assertFalse(NumberChatState.separator(0, 0))
    }
    @Test fun numberValidationPreservesLeadingZerosAndRejectsOtherCharacters() {
        assertTrue(NumberChatState.validNumber("00123456"))
        listOf("", "1234567", "123456789", "1234 5678", "abcdefgh", "１２３４５６７８", "12345678\n").forEach {
            assertFalse(it, NumberChatState.validNumber(it))
        }
        assertEquals("0012 3456", NumberChatState.formatted("00123456"))
    }
    @Test fun listTimesUseLocalCalendarAcrossMidnightAndYearBoundary() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-01-01T00:10:00Z").toEpochMilli()
        fun time(value: String) = NumberChatState.listTime(Instant.parse(value).toEpochMilli(), now, "yesterday", zone)
        assertEquals("08:00", time("2026-01-01T00:00:00Z"))
        assertEquals("yesterday", time("2025-12-31T15:59:00Z"))
        assertEquals("12/30", time("2025-12-30T00:00:00Z"))
    }
}
