// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.testing.*
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HybridScreenPortTest {
    private val clock = "com.google.android.deskclock"
    private class Phone(var pkg: String, var text: String = "safe") : ScreenPort by FakeScreen() {
        var reads = 0
        var actions = 0
        var atAction: () -> Unit = {}
        var afterAction: () -> Unit = {}
        var fail = false
        override suspend fun foregroundPackages() = listOf(pkg)
        override suspend fun snapshot(): ScreenSnapshot {
            reads++
            return ScreenSnapshot("s$reads", pkg, listOf(UiElement("@e1", "text", text, 1, 1,
                listOf(0, 0, 10, 10), null, false, false, false)), 100, 100)
        }
        override suspend fun peek() = snapshot()
        private fun act(): ActionResult {
            atAction(); actions++; afterAction()
            if (fail) throw IllegalStateException("failed after dispatch")
            return ActionResult.Ok()
        }
        override suspend fun tapXY(x: Int, y: Int) = act()
        override suspend fun openApp(packageName: String, cold: Boolean) = act()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = act()
        override suspend fun screenshotPng(): ByteArray { atAction(); return byteArrayOf(1, 2) }
    }

    @Test fun blockedTargetPromotesBeforeOneActionAndPrivateRequestsNeverReachNode() = runTest {
        val c = HybridCompute(true)
        val p = Phone(clock)
        val port = CommunityScreenPort(p, HybridScreenBoundary(c) { 2 })
        val node = fakeModel { answer("safe") }
        val own = fakeModel { repeat(2) { answer("private") } }
        val model = HybridModel(node, own, c) { 2 }
        model.complete("base", emptyList(), emptyList())
        p.atAction = { assertEquals(HybridUpgrade(2, HybridReason.AppNotAllowed), c.upgrade) }
        port.openApp("com.eg.android.AlipayGphone", false)
        p.pkg = "com.eg.android.AlipayGphone"
        p.text = "OTP 123456"
        val raw = port.snapshot()
        repeat(2) { model.complete("base", listOf(Message(Role.User, listOf(Part.Text(raw.toString())), Author.User)), emptyList()) }
        assertEquals(1, p.actions)
        assertEquals(1, p.reads)
        assertEquals(1, node.requests.size)
        assertEquals(2, own.requests.size)
        assertTrue(own.requests.first().messages.toString().contains("123456"))
    }

    @Test fun sensitiveInputPromotesBeforeDispatch() = runTest {
        val c = HybridCompute(true)
        val p = Phone(clock, "OTP")
        val port = CommunityScreenPort(p, HybridScreenBoundary(c) { 3 })
        p.atAction = { assertEquals(HybridUpgrade(3, HybridReason.SensitiveContent), c.upgrade) }
        port.typeText(null, null, "123456")
        assertEquals(1, p.actions)
        assertEquals(1, p.reads)
    }

    @Test fun ownerChangeAfterActionPromotesWithoutReplayingAction() = runTest {
        val c = HybridCompute(true)
        val p = Phone(clock)
        val port = CommunityScreenPort(p, HybridScreenBoundary(c) { 4 })
        p.afterAction = { p.pkg = "com.eg.android.AlipayGphone" }
        port.tapXY(1, 1)
        assertEquals(1, p.actions)
        assertEquals(HybridUpgrade(4, HybridReason.AppNotAllowed), c.upgrade)
    }

    @Test fun dispatchedFailureIsNeverRetried() = runTest {
        val c = HybridCompute(true)
        val p = Phone("com.eg.android.AlipayGphone")
        val port = CommunityScreenPort(p, HybridScreenBoundary(c) { 4 })
        p.fail = true
        try { port.tapXY(1, 1); fail("expected dispatch error") }
        catch (e: IllegalStateException) { assertEquals("failed after dispatch", e.message) }
        assertEquals(1, p.actions)
        assertNotNull(c.upgrade)
    }

    @Test fun imageAcquisitionPromotesBeforeReadingBytes() = runTest {
        val c = HybridCompute(true)
        val p = Phone(clock)
        val port = CommunityScreenPort(p, HybridScreenBoundary(c) { 5 })
        p.atAction = { assertEquals(HybridUpgrade(5, HybridReason.Image), c.upgrade) }
        assertArrayEquals(byteArrayOf(1, 2), port.screenshotPng())
    }

    @Test fun safeScreenAndActionsStayOnCommunityUntilExplicitPromotion() = runTest {
        val c = HybridCompute(true)
        val p = Phone(clock)
        val port = CommunityScreenPort(p, HybridScreenBoundary(c) { 1 })
        assertFalse(port.snapshot().communityHidden)
        port.tapXY(1, 1)
        assertNull(c.upgrade)
        assertEquals(1, p.actions)
    }

    @Test fun plainCommunityStillRejectsAndMasks() = runTest {
        val p = Phone("com.eg.android.AlipayGphone", "private")
        val port = CommunityScreenPort(p)
        assertTrue(port.snapshot().communityHidden)
        try { port.openApp("com.eg.android.AlipayGphone", false); fail("expected denial") }
        catch (e: AppAccessDenied) { assertEquals(CommunityPolicy.ERROR, e.code) }
        assertEquals(0, p.actions)
        assertNull(port.screenshotPng())
    }
}
