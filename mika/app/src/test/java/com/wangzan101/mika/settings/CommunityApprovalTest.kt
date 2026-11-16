// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.coroutines.test.runTest

class CommunityApprovalTest {
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
