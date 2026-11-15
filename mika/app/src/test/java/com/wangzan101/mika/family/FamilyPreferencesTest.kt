// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.core.action.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class FamilyPreferencesTest {
    private class Storage {
        val data = mutableMapOf<String, String>()
        val commits = mutableListOf<Map<String, String>>()
        var fail = false
        fun phone(peers: List<String> = listOf("paired")) = FamilyPreferences(data::get, { values ->
            if (fail) error("disk failure")
            commits += values.toMap(); data.putAll(values)
        }, { peers })
    }
    @Test fun joiningCommitsTrustAnnouncementAndFixedNoticesOnce() {
        val disk = Storage()
        val phone = disk.phone(listOf("first", "second"))
        val pair = FamilyPair(buildJsonObject { put("id", "new-family") })
        phone.joined(pair)
        assertEquals(setOf("pairs", "notices", "joinedAnnouncement"), disk.commits.single().keys)
        assertEquals("new-family", disk.data["joinedAnnouncement"])
        assertTrue(Json.parseToJsonElement(disk.data.getValue("pairs")).jsonObject.containsKey(pair.id))
        assertEquals(listOf("first", "second"), phone.pending().map { it.str("pair") })
        assertTrue(phone.pending().all { it.str("category") == "family_added" && it.keys == setOf("id", "pair", "category") })
        disk.phone().joined(pair)
        assertEquals(1, disk.commits.size)
        assertFalse(phone.policy().active)
    }
    @Test fun failedJoinCommitCanRetryAndFirstFamilyHasNoNoticeRecipients() {
        val disk = Storage()
        val phone = disk.phone(emptyList())
        val pair = FamilyPair(buildJsonObject { put("id", "first-family") })
        disk.fail = true
        assertTrue(runCatching { phone.joined(pair) }.isFailure)
        assertTrue(disk.data.isEmpty())
        disk.fail = false
        phone.joined(pair)
        assertTrue(phone.pending().isEmpty())
        assertEquals("first-family", disk.data["joinedAnnouncement"])
    }
    @Test fun onlyApprovalDowngradesQueueAtomicallyAndSurviveRestart() {
        val disk = Storage(); val phone = disk.phone()
        assertFalse(phone.policy().active); assertTrue(phone.pending().isEmpty())
        phone.setPolicy(FamilyCategory.Payment, FamilyMode.Notify)
        phone.setPolicy(FamilyCategory.Payment, FamilyMode.Private)
        phone.setPolicy(FamilyCategory.Payment, FamilyMode.Approve)
        assertTrue(phone.pending().isEmpty())
        phone.setPolicy(FamilyCategory.Payment, FamilyMode.Notify)
        val restarted = disk.phone()
        assertEquals(FamilyMode.Notify, restarted.policy().mode("pay"))
        assertEquals(listOf("lowered_payment"), restarted.pending().map { it.str("category") })
        assertEquals(setOf("policy_payment", "notices"), disk.commits.last().keys)
        restarted.setPolicy(FamilyCategory.Payment, FamilyMode.Notify)
        assertEquals(1, restarted.pending().size)
    }
    @Test fun failedCommitLeavesOldPolicyAndQueueTogether() {
        val disk = Storage(); val phone = disk.phone()
        phone.setPolicy(FamilyCategory.Transfer, FamilyMode.Approve)
        disk.fail = true
        try { phone.setPolicy(FamilyCategory.Transfer, FamilyMode.Private); fail("expected disk failure") } catch (_: IllegalStateException) {}
        assertEquals(FamilyMode.Approve, phone.policy().mode("transfer")); assertTrue(phone.pending().isEmpty())
    }
    @Test fun familyPhoneWritesOnlyItsOwnSettingsAndCannotChangeElderOrLearnPrivateOperations() {
        val elderDisk = Storage(); val familyDisk = Storage()
        val elder = elderDisk.phone(); val family = familyDisk.phone(emptyList())
        elder.setPolicy(FamilyCategory.Payment, FamilyMode.Approve)
        val frozen = elder.policy()
        family.setPolicy(FamilyCategory.Payment, FamilyMode.Private)
        family.setPolicy(FamilyCategory.Install, FamilyMode.Notify)
        assertEquals(FamilyMode.Approve, elder.policy().mode("pay"))
        assertEquals(FamilyMode.Private, elder.policy().mode("install"))
        assertTrue(elder.pending().isEmpty()); assertTrue(family.pending().isEmpty())
        elder.setPolicy(FamilyCategory.Payment, FamilyMode.Private)
        assertEquals(FamilyMode.Approve, frozen.mode("pay"))
        assertEquals(FamilyMode.Private, elder.policy().mode("pay"))
    }
    @Test fun acknowledgementDoesNotLoseNoticesQueuedWhileSendingAndNeverRetargets() {
        val disk = Storage(); val phone = disk.phone()
        phone.queueNotice("stranger", "pay"); assertTrue(phone.pending().isEmpty())
        phone.queueNotice("paired", "pay"); val first = phone.pending().single()
        phone.queueNotice("paired", "install")
        phone.acknowledge(first.str("id"))
        assertEquals(listOf("operation_install"), phone.pending().map { it.str("category") })
        assertEquals("paired", phone.pending().single().str("pair"))
        assertEquals(setOf("id", "pair", "category"), phone.pending().single().keys)
    }
}
