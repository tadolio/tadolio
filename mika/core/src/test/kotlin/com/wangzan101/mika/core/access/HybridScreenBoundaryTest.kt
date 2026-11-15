// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.testing.*
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HybridScreenBoundaryTest {
    private val clock = "com.google.android.deskclock"
    private fun element(text: String, secret: Boolean = false) = UiElement(
        "@e1", "text", text, 1, 1, listOf(0, 0, 10, 10), null, false, false, false, secret = secret)
    private fun screen(text: String = "weather") = ScreenSnapshot("s", clock, listOf(element(text)), 100, 100)
    private fun window(nodes: List<UiTreeNode> = emptyList()) = UiWindowTree(
        1, "application", 0, null, clock, true, true, nodes)
    private fun message(s: ScreenSnapshot) = Message(Role.User, listOf(Part.Text(s.toString())), Author.User)

    @Test fun blockedOwnersPromoteBeforeRawReaderRuns() = runTest {
        for (owners in listOf(emptyList(), listOf(null), listOf("com.tencent.mm"), listOf(clock, "com.tencent.mm"))) {
            val compute = HybridCompute(true)
            val boundary = HybridScreenBoundary(compute) { 3 }
            var reads = 0
            val raw = screen("private")
            val captured = boundary.capture({ owners }) {
                assertEquals(HybridUpgrade(3, HybridReason.AppNotAllowed), compute.upgrade)
                reads++; raw
            }
            assertSame(raw, captured)
            assertEquals(1, reads)
        }
    }

    @Test fun safeScreenUsesNodeButTriggerScreenAndSubsequentStepsOnlyReachOwn() = runTest {
        val triggers = listOf(
            screen().copy(packageName = "com.tencent.mm") to HybridReason.AppNotAllowed,
            screen().copy(protected = true) to HybridReason.WindowHidden,
            screen().copy(elements = listOf(element("", secret = true))) to HybridReason.PasswordField,
            screen("验证码\n123456") to HybridReason.SensitiveContent,
            screen("4111 1111 1111 1111") to HybridReason.SensitiveContent,
            screen("seed phrase: private-wallet-words") to HybridReason.SensitiveContent,
        )
        for ((trigger, reason) in triggers) {
            val compute = HybridCompute(true)
            var step = 1
            var reads = 0
            val boundary = HybridScreenBoundary(compute) { step }
            val node = fakeModel { answer("safe") }
            val own = fakeModel { repeat(2) { answer("own") } }
            val model = HybridModel(node, own, compute) { step }
            val history = mutableListOf<Message>()
            for (raw in listOf(screen(), trigger, screen("safe again"))) {
                val captured = boundary.capture({ listOf(clock) }) { reads++; raw }
                assertSame(raw, captured)
                history += message(captured)
                model.complete("BASE", history.toList(), emptyList())
                step++
            }
            assertEquals(reason.toString(), HybridUpgrade(2, reason), compute.upgrade)
            assertEquals(3, reads)
            assertEquals(1, node.requests.size)
            assertEquals(2, own.requests.size)
            assertFalse(node.requests.single().messages.toString().contains(trigger.toString()))
            assertEquals(message(trigger), own.requests.first().messages[1])
            assertEquals(history, own.requests.last().messages)
            assertTrue(compute.usedCommunity)
        }
    }

    @Test fun windowTreeAndNeighboringNodesAreCheckedBeforeSerialization() = runTest {
        val node = UiTreeNode("text", emptyList(), text = "123456")
        val screens = listOf(
            screen().copy(windows = listOf(window(listOf(node.copy(secret = true))))) to HybridReason.PasswordField,
            screen().copy(windows = listOf(window(listOf(node.copy(text = "验证码", children = listOf(node)))))) to HybridReason.SensitiveContent,
            screen().copy(windows = listOf(window().copy(title = "OTP 654321"))) to HybridReason.SensitiveContent,
            screen().copy(elements = listOf(element("验证码"), element("123456"))) to HybridReason.SensitiveContent,
        )
        for ((raw, reason) in screens) {
            val compute = HybridCompute(true)
            assertSame(raw, HybridScreenBoundary(compute) { 2 }.captured(raw, listOf(clock), listOf(clock)))
            assertEquals(HybridUpgrade(2, reason), compute.upgrade)
        }
    }

    @Test fun ownershipChangePromotesWithoutRecapture() = runTest {
        val compute = HybridCompute(true)
        val boundary = HybridScreenBoundary(compute) { 4 }
        var owners = listOf(clock)
        var reads = 0
        val raw = screen("private screen during transition")
        assertSame(raw, boundary.capture({ owners }) { reads++; owners = listOf("com.eg.android.AlipayGphone"); raw })
        assertEquals(1, reads)
        assertEquals(HybridUpgrade(4, HybridReason.AppNotAllowed), compute.upgrade)
    }

    @Test fun duplicatedMnemonicInFlatAndWindowTreesStillPromotes() = runTest {
        val words = List(24) { "abandon" }.joinToString(" ")
        val raw = screen(words).copy(windows = listOf(window(listOf(UiTreeNode("text", emptyList(), text = words)))))
        val compute = HybridCompute(true)
        HybridScreenBoundary(compute) { 2 }.captured(raw, listOf(clock), listOf(clock))
        assertEquals(HybridUpgrade(2, HybridReason.SensitiveContent), compute.upgrade)
    }

    @Test fun ordinaryNumbersAndSafeSnapshotsRemainCommunity() = runTest {
        val raw = screen("2026-11-05 13800138000 123456").copy(windows = listOf(window()))
        val compute = HybridCompute(true)
        assertSame(raw, HybridScreenBoundary(compute) { 1 }.capture({ listOf(clock) }) { raw })
        assertNull(compute.upgrade)
        assertFalse(compute.usedCommunity)
    }

    @Test fun allowedOwnershipChangeAndUnreadableWindowsPromote() = runTest {
        for (unreadable in listOf(false, true)) {
            val compute = HybridCompute(true)
            HybridScreenBoundary(compute) { 2 }.captured(
                screen().copy(unreadableApp = if (unreadable) clock else null), listOf(clock),
                if (unreadable) listOf(clock) else listOf("com.google.android.calculator"))
            assertEquals(HybridUpgrade(2, HybridReason.WindowHidden), compute.upgrade)
        }
    }

    @Test fun alreadyMaskedSnapshotIsRejectedInsteadOfClaimingLosslessContinuation() = runTest {
        for (upgraded in listOf(false, true)) {
            val compute = HybridCompute(true, remote = upgraded)
            try {
                HybridScreenBoundary(compute) { 1 }.captured(
                    screen().copy(communityHidden = true), listOf(clock), listOf(clock))
                fail("requires raw reader")
            } catch (e: IllegalStateException) { assertEquals("hybrid_requires_raw_snapshot", e.message) }
        }
    }

    @Test fun readerFailureDoesNotRetryAndKeepsExistingPromotion() = runTest {
        val compute = HybridCompute(true)
        var reads = 0
        val error = IllegalStateException("reader failed")
        try {
            HybridScreenBoundary(compute) { 2 }.capture({ listOf("com.eg.android.AlipayGphone") }) { reads++; throw error }
            fail("reader failure must propagate")
        } catch (e: IllegalStateException) { assertSame(error, e) }
        assertEquals(1, reads)
        assertEquals(HybridUpgrade(2, HybridReason.AppNotAllowed), compute.upgrade)
    }
    @Test fun nodeIdentityMismatchPromotesBeforeRawContentCanReachTransport() = runTest {
        for ((windowId, pkg) in listOf(2 to clock, 1 to "com.eg.android.AlipayGphone", 1 to null)) {
            val audit = HybridNodeOwnership()
            audit.observe(1, clock, windowId, pkg)
            // Later valid nodes must not erase a failure from the same capture.
            audit.observe(1, clock, 1, clock)
            assertFalse(audit.trusted)
            val compute = HybridCompute(true)
            val boundary = HybridScreenBoundary(compute) { 5 }
            val raw = screen("private identity mismatch")
            val captured = boundary.captured(raw, listOf(clock), listOf(clock), audit.trusted)
            assertSame(raw, captured)
            assertFalse(boundary.community)
            assertEquals(HybridUpgrade(5, HybridReason.WindowHidden), compute.upgrade)
            val node = fakeModel { answer("must not run") }
            val own = fakeModel { answer("own") }
            HybridModel(node, own, compute) { 5 }.complete("BASE", listOf(message(captured)), emptyList())
            assertEquals(0, node.requests.size)
            assertEquals(listOf(message(raw)), own.requests.single().messages)
            assertFalse(compute.usedCommunity)
        }
    }

    @Test fun validNodeOwnershipDoesNotPromoteAndCaptureAuditsAreIndependent() = runTest {
        val rejected = HybridNodeOwnership()
        rejected.observe(1, null, 1, null)
        assertFalse(rejected.trusted)
        val audit = HybridNodeOwnership()
        audit.observe(1, clock, 1, clock)
        assertTrue(audit.trusted)
        val compute = HybridCompute(true)
        val boundary = HybridScreenBoundary(compute) { 1 }
        val raw = screen()
        assertSame(raw, boundary.captured(raw, listOf(clock), listOf(clock), audit.trusted))
        assertTrue(boundary.community)
        assertNull(compute.upgrade)
    }

}
