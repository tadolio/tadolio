// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.settings.DeferredSetupPolicy
import org.junit.Assert.*
import org.junit.Test

class DeferredSetupTest {
    @Test fun batteryWaitsForScheduleOrBackgroundLossAndStopsWhenAllowed() {
        assertFalse(DeferredSetupPolicy.battery(false, false, false))
        assertTrue(DeferredSetupPolicy.battery(true, false, false))
        assertTrue(DeferredSetupPolicy.battery(false, true, false))
        assertFalse(DeferredSetupPolicy.battery(true, true, true))
    }
    @Test fun backgroundLossRequiresARealHelperDeathAndExcludesExplicitExit() {
        assertTrue(DeferredSetupPolicy.backgroundLoss(true, false, true, false))
        assertFalse(DeferredSetupPolicy.backgroundLoss(false, false, true, false))
        assertFalse(DeferredSetupPolicy.backgroundLoss(true, true, true, false))
        assertFalse(DeferredSetupPolicy.backgroundLoss(true, false, false, false))
        assertFalse(DeferredSetupPolicy.backgroundLoss(true, false, true, true))
    }
    @Test fun dismissAndFirstUseTriggersSurviveRecreation() {
        val prefs = com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs()
        val state = com.wangzan101.mika.settings.DeferredSetup(prefs)
        assertFalse(state.batteryNeeded())
        assertFalse(state.hasResult())
        state.needBattery()
        state.recordResult()
        state.dismissReferrer()
        val restored = com.wangzan101.mika.settings.DeferredSetup(prefs)
        assertTrue(restored.batteryNeeded())
        assertTrue(restored.hasResult())
        assertTrue(restored.referrerDismissed())
    }
    @Test fun referrerNeedsResultOpenWindowAndUndismissedState() {
        assertFalse(DeferredSetupPolicy.referrer(false, true, false))
        assertFalse(DeferredSetupPolicy.referrer(true, false, false))
        assertFalse(DeferredSetupPolicy.referrer(true, true, true))
        assertTrue(DeferredSetupPolicy.referrer(true, true, false))
    }
}
