// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.wangzan101.mika.core.access.HybridCompute
import com.wangzan101.mika.core.access.HybridScreenBoundary
import kotlinx.coroutines.test.runTest

class CommunityApprovalTest {
    private fun withControl(test: (ApprovalControl) -> Unit) {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            test(ApprovalControl(ApprovalStore(File(directory, "approval.json"))))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun hybridRetainsOwnStartupPolicyUntilSharedPromotion() = runTest {
        val boundary = HybridScreenBoundary(HybridCompute(true)) { 2 }
        lateinit var hybrid: RunApproval
        withControl { control ->
            control.setAutonomous(true)
            hybrid = control.start(community = true, hybridBoundary = boundary)
            assertTrue(hybrid.startedAutonomous)
            assertNull(hybrid.gate())
            assertFalse(hybrid.autonomous())
        }
        boundary.beforeTarget("com.tencent.mm")
        assertTrue(hybrid.autonomous())
    }

    @Test fun localHybridRetainsSettingUpdatesAndStopsFollowingAfterEnd() = runTest {
        val boundary = HybridScreenBoundary(HybridCompute(true)) { 3 }
        lateinit var hybrid: RunApproval
        withControl { control ->
            hybrid = control.start(community = true, hybridBoundary = boundary)
            assertFalse(hybrid.startedAutonomous)
            assertNotNull(hybrid.gate())
            control.setAutonomous(true)
            assertFalse(hybrid.autonomous())
            control.end(hybrid)
            control.setAutonomous(false)
        }
        boundary.beforeTarget("com.tencent.mm")
        assertTrue(hybrid.autonomous())
        assertNotNull(hybrid.gate())
    }

    @Test fun switchingOffDuringCommunityIsRetainedAfterPromotion() = runTest {
        val boundary = HybridScreenBoundary(HybridCompute(true)) { 4 }
        lateinit var hybrid: RunApproval
        withControl { control ->
            control.setAutonomous(true)
            hybrid = control.start(community = true, hybridBoundary = boundary)
            control.setAutonomous(false)
            assertFalse(hybrid.autonomous())
        }
        boundary.beforeTarget("com.tencent.mm")
        assertFalse(hybrid.autonomous())
        // Own startup semantics retain the original gate decision for the whole run.
        assertTrue(hybrid.startedAutonomous)
        assertNull(hybrid.gate())
    }

    @Test fun remoteHybridStartsOwnWithFixedAskAndIgnoresGlobalChanges() {
        val boundary = HybridScreenBoundary(HybridCompute(true, remote = true)) { 1 }
        withControl { control ->
            control.setAutonomous(true)
            val hybrid = control.start(remote = true, community = true, hybridBoundary = boundary)
            val own = control.start(remote = true)
            control.setAutonomous(false)
            control.setAutonomous(true)
            assertFalse(boundary.community)
            for (run in listOf(hybrid, own)) {
                assertFalse(run.startedAutonomous)
                assertFalse(run.autonomous())
                assertNotNull(run.gate())
            }
        }
    }

    @Test fun hybridBoundaryRequiresCommunityStart() {
        val boundary = HybridScreenBoundary(HybridCompute(true)) { 1 }
        assertThrows(IllegalArgumentException::class.java) {
            RunApproval(true, hybridBoundary = boundary)
        }
    }

    @Test fun globalAndPerRunSettingsCannotEnableCommunityAutonomy() {
        val dir=kotlin.io.path.createTempDirectory().toFile()
        try {
            val store=ApprovalStore(File(dir,"approval.json"));store.setAutonomous(true)
            val control=ApprovalControl(store)
            val community=control.start(community=true)
            val own=control.start()
            assertFalse(community.startedAutonomous);assertNotNull(community.gate())
            community.set(true);control.setAutonomous(true)
            assertFalse(community.autonomous());assertTrue(own.autonomous())
            control.setAutonomous(false);assertFalse(own.autonomous())
        } finally { dir.deleteRecursively() }
    }
}
