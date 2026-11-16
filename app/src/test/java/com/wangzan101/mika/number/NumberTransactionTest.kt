// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NumberTransactionTest {
    private var data: String? = null
    private fun store() = NumberStore({ data }, { data = it })
    private fun message(id: String, kind: String = "ask", thread: String = "first",
                        peer: String = "12345678", outgoing: Boolean = false,
                        expires: Long? = null): JsonObject = buildJsonObject {
        put("id", id); put("from", if (outgoing) "87654321" else peer)
        put("to", if (outgoing) peer else "87654321"); put("time", 1000L)
        NumberEnvelope(kind, "subject", "body", thread, expires = expires).encode()
            .forEach { (key, value) -> put(key, value) }
    }

    @Test fun processedTransactionReopensOnlyForNewSubstantiveMessage() {
        val store = store()
        store.add(message("first"), false)
        assertEquals("pending", store.transactions().single().state)
        store.setState("12345678", "first", "processed")
        assertEquals("processed", store().transactions().single().state)
        store.add(message("ack", "ack"), false)
        assertEquals("processed", store.transactions().single().state)
        assertFalse(store.add(message("first"), false))
        assertEquals("processed", store.transactions().single().state)
        store.add(message("second"), false)
        assertEquals("pending", store().transactions().single().state)
        assertEquals(1, store.transactions().size)
    }

    @Test fun ownerReplyCompletesButReceiptDoesNotCompleteOrEstablishTrust() {
        val store = store()
        store.add(message("first"), false)
        store.add(message("receipt", "ack", outgoing = true), true)
        assertEquals("pending", store.transactions().single().state)
        assertFalse(store.ownerReplied("12345678"))
        store.add(message("reply", "reply", outgoing = true), true)
        assertEquals("processed", store().transactions().single().state)
        assertTrue(store().ownerReplied("12345678"))
        store.add(message("again"), false)
        assertEquals("pending", store.transactions().single().state)
    }

    @Test fun peersCannotControlEachOthersThreadState() {
        val store = store()
        store.add(message("first"), false)
        store.add(message("other", peer = "11111111"), false)
        store.setState("12345678", "first", "filtered")
        assertEquals("filtered", store.transactions().single { it.number == "12345678" }.state)
        assertEquals("pending", store.transactions().single { it.number == "11111111" }.state)
        store.setState("12345678", "first", "pending")
        assertTrue(store.transactions().all { it.state == "pending" })
    }

    @Test fun expiredTransactionIsArchivedAndReceiptOnlyThreadHasNoCard() {
        val store = store()
        store.add(message("first", expires = 2000L), false)
        store.add(message("ack", "ack", "receipt-only"), false)
        assertEquals("pending", store.transactions(1999L).single().state)
        assertEquals("processed", store().transactions(2000L).single().state)
    }

    @Test fun receiptIsClaimedOncePerPeerAndThreadAcrossRestart() {
        val store = store()
        store.add(message("ack", "ack", "ack-only"), false)
        assertFalse(store.claimReceipt("12345678", "ack-only"))
        store.add(message("first"), false)
        store.recordTriage("first", NumberTriageResult("normal", "summary"))
        assertTrue(store.claimReceipt("12345678", "first"))
        store.add(message("second"), false)
        assertFalse(store().claimReceipt("12345678", "first"))
        store.add(message("other", peer = "11111111"), false)
        store.recordTriage("other", NumberTriageResult("normal", "summary"))
        assertTrue(store.claimReceipt("11111111", "first"))
    }

    @Test fun failedSendsReleaseTheirClaimsForRetry() {
        val store = store()
        store.add(message("first"), false)
        store.recordTriage("first", NumberTriageResult("normal", "summary"))
        assertTrue(store.claimReceipt("12345678", "first"))
        store.releaseReceipt("12345678", "first")
        assertTrue(store().claimReceipt("12345678", "first"))
        assertTrue(store.claimAutoReply("first"))
        store.recordAutoReply("first", NumberAutoReplyResult("reply", "hi", "answered"))
        store.releaseAutoReply("first")
        assertEquals(null, store().transactions().single().autoNote)
        assertTrue(store.claimAutoReply("first"))
    }

    @Test fun noReceiptWhenOwnerStartedTheThread() {
        val store = store()
        store.add(message("mine", thread = "mine", outgoing = true), true)
        store.add(message("answer", "reply", thread = "mine"), false)
        store.recordTriage("answer", NumberTriageResult("normal", "summary"))
        assertFalse(store.claimReceipt("12345678", "mine"))
    }

    @Test fun spamIsFilteredAndCannotClaimReceiptAndAckCannotBeTriaged() {
        val store = store()
        store.add(message("first"), false)
        store.recordTriage("first", NumberTriageResult("spam", "filtered summary"))
        assertEquals("filtered", store().transactions().single().state)
        assertFalse(store.claimReceipt("12345678", "first"))
        store.add(message("ack", "ack"), false)
        store.recordTriage("ack", NumberTriageResult("urgent", "ignored"))
        assertEquals("filtered summary", store.transactions().single().summary)
        store.add(message("again"), false)
        assertEquals("pending", store.transactions().single().state)
    }

    @Test fun receiptsRequireClassificationAndSkipBlockedExpiredOrFirstSpam() {
        val store = store()
        store.add(message("first"), false)
        assertFalse(store.claimReceipt("12345678", "first"))
        store.recordTriage("first", NumberTriageResult("normal", "summary"))
        assertFalse(store.claimReceipt("12345678", "first", blocked = true))
        assertTrue(store.claimReceipt("12345678", "first"))
        store.add(message("expired", thread = "expired", expires = 2000), false)
        store.recordTriage("expired", NumberTriageResult("normal", "summary"))
        assertFalse(store.claimReceipt("12345678", "expired", now = 2000))
        store.add(message("spam", thread = "spam"), false)
        store.recordTriage("spam", NumberTriageResult("spam", "summary"))
        store.add(message("followup", thread = "spam"), false)
        store.recordTriage("followup", NumberTriageResult("normal", "summary"))
        assertFalse(store.claimReceipt("12345678", "spam"))
    }

    @Test fun legacyMessagesRemainReadableWithoutRewritingSignedFields() {
        val store = store()
        val legacy = buildJsonObject {
            put("id", "legacy"); put("from", "12345678"); put("to", "87654321")
            put("time", 1000L); put("text", "old plain text")
        }
        store.add(legacy, false)
        val transaction = store().transactions().single()
        assertEquals("legacy", transaction.thread)
        assertEquals("old plain text", transaction.subject)
        assertEquals(transaction.subject, transaction.summary)
        assertEquals("normal", transaction.tier)
        assertFalse(store.messages().single().containsKey("kind"))
    }

    @Test fun automaticReplyStaysPendingAndDecisionsSurviveRestart() {
        val store = store()
        store.add(message("first"), false)
        store.recordTriage("first", NumberTriageResult("urgent", "summary"))
        assertTrue(store.claimAutoReply("first"))
        assertFalse(store().claimAutoReply("first"))
        store.recordAutoReply("first", NumberAutoReplyResult("reply", "where?", "Clarifying"))
        store.add(JsonObject(message("auto", "reply", outgoing = true) + ("auto" to JsonPrimitive(true))), true)
        val card = store().transactions().single()
        assertEquals("pending", card.state)
        assertEquals("urgent", card.tier)
        assertEquals(1, card.autoReplies)
        assertEquals("Clarifying", card.autoNote)
        assertFalse(card.takenOver)
        store.add(message("next"), false)
        store.recordAutoReply("next", NumberAutoReplyResult("handoff", "", "Owner decides"))
        assertEquals("pending", store().transactions().single().state)
        store.recordAutoReply("next", NumberAutoReplyResult("close", "", "Done"))
        assertEquals("processed", store().transactions().single().state)
        assertEquals("Done", store().transactions().single().autoNote)
    }

    @Test fun takeoverPersistsAcrossNewMessagesAndCanBeReleased() {
        val store = store()
        store.add(message("first"), false)
        store.setTakenOver("12345678", "first", true)
        store.add(message("next"), false)
        assertTrue(store().takenOver("12345678", "first"))
        store.setTakenOver("12345678", "first", false)
        assertFalse(store().takenOver("12345678", "first"))
        store.add(JsonObject(message("owner", "inform", outgoing = true) + ("takenOver" to JsonPrimitive(true))), true)
        assertTrue(store().takenOver("12345678", "first"))
        assertFalse(store().takenOver("11111111", "first"))
    }

    @Test fun dailyReplyCountIncludesDifferentPeersAndContactExcludesAck() {
        val store = store()
        store.add(message("ack", "ack"), false)
        assertFalse(store.contacted("12345678"))
        store.add(message("first"), false)
        assertTrue(store.contacted("12345678"))
        for ((id, peer) in listOf("a" to "12345678", "b" to "11111111")) {
            store.add(JsonObject(message(id, "reply", peer = peer, outgoing = true) +
                ("auto" to JsonPrimitive(true))), true)
        }
        assertEquals(2, store().autoRepliesToday("87654321", 1000))
        assertEquals(0, store().autoRepliesToday("87654321", 1000 + 86400000L))
        assertEquals(1000L, store().lastAutoReplyAt("12345678", "first"))
    }
}
