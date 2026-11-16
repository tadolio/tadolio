// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.core.action.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FamilyOptInTest {
    private class Transport : FamilyTransport {
        var sends = 0
        var request: FamilyRequest? = null
        var answer: Boolean? = true
        var offline = false
        override suspend fun send(request: FamilyRequest) { sends++; if (offline) error("offline"); this.request = request }
        override suspend fun decisions() = request?.let { r -> answer?.let { listOf(FamilyDecision(r.id, r.pair, r.digest, it, r.expires)) } }.orEmpty()
    }
    @Test fun pairedDefaultsSendNothingIncludingUnselectedCategories() = runTest {
        val transport = Transport()
        val sharing = FamilySharing(FamilyPolicy(), FamilyApproval(transport), "paired") { error("private notice") }
        for (category in FamilyCategory.entries.map { it.wire } + listOf("send", "delete", "other")) {
            assertTrue(sharing.authorize("digest", category)); sharing.completed(category)
        }
        assertEquals(0, transport.sends)
    }
    @Test fun notifyNeverWaitsForOfflineTransportAndOnlyQueuesAfterCompletion() = runTest {
        val transport = Transport().apply { offline = true }
        val notices = mutableListOf<String>()
        val policy = FamilyPolicy().changed(FamilyCategory.Transfer, FamilyMode.Notify)
        val sharing = FamilySharing(policy, FamilyApproval(transport), "paired", notices::add)
        assertTrue(sharing.authorize("digest", "transfer"))
        assertTrue(notices.isEmpty()); assertEquals(0, transport.sends)
        sharing.completed("transfer"); sharing.completed("pay")
        assertEquals(listOf("transfer"), notices); assertEquals(0, transport.sends)
    }
    @Test fun approvalRefusalTimeoutAndOfflineUseFrozenPolicy() = runTest {
        for (path in listOf("approve", "deny", "timeout", "offline")) {
            val t = Transport().apply { answer = when(path) { "approve" -> true; "timeout" -> null; else -> false }; offline = path == "offline" }
            var time = 1000L
            val frozen = FamilyPolicy().changed(FamilyCategory.Payment, FamilyMode.Approve)
            val sharing = FamilySharing(frozen, FamilyApproval(t, { time }, { time += 25 }, 100), "paired") { error("approval is not a notice") }
            val edited = frozen.changed(FamilyCategory.Payment, FamilyMode.Private)
            assertEquals(FamilyMode.Private, edited.mode("pay"))
            assertEquals(path == "approve", sharing.authorize("bound", "pay"))
            assertEquals(1, t.sends)
            assertTrue(sharing.authorize("private", "transfer")); assertEquals(1, t.sends)
        }
    }
    @Test fun missingFamilyFallsBackWithoutSending() = runTest {
        val t = Transport()
        val sharing = FamilySharing(FamilyPolicy().changed(FamilyCategory.Install, FamilyMode.Approve), FamilyApproval(t), null) { error("no pair") }
        assertTrue(sharing.authorize("digest", "install")); assertEquals(0, t.sends)
    }
    @Test fun onlyLoweringApprovalNotifiesAndPoliciesBelongToEachPhone() {
        val elder = FamilyPolicy().changed(FamilyCategory.Payment, FamilyMode.Approve)
        val family = FamilyPolicy().changed(FamilyCategory.Transfer, FamilyMode.Notify)
        for (category in FamilyCategory.entries) for (mode in FamilyMode.entries) {
            assertEquals(category == FamilyCategory.Payment && mode != FamilyMode.Approve, elder.lowered(category, mode))
        }
        assertEquals(FamilyMode.Approve, elder.mode("pay"))
        assertEquals(FamilyMode.Private, elder.mode("transfer"))
        assertEquals(FamilyMode.Notify, family.mode("transfer"))
        assertEquals(FamilyMode.Private, family.mode("pay"))
    }
}
