// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureFlagsStepLimitTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun defaultsToFortySteps() {
        assertEquals(40, flags().taskStepLimit())
    }

    @Test fun keepsAChosenLimit() {
        val f = flags()
        f.setTaskStepLimit(60)
        assertEquals(60, f.taskStepLimit())
        f.setTaskStepLimit(null)
        assertNull(f.taskStepLimit())
    }

    @Test fun preservesExistingPreferencesAfterRecreation() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val old = FeatureFlags(prefs)
        old.setTaskStepLimit(75)
        assertEquals(75, FeatureFlags(prefs).taskStepLimit())
        old.setTaskStepLimit(null)
        assertNull(FeatureFlags(prefs).taskStepLimit())
        old.setTaskStepLimit(0)
        assertNull(FeatureFlags(prefs).taskStepLimit())
    }

    @Test fun clampsIntoRange() {
        val f = flags()
        f.setTaskStepLimit(0)
        assertNull(f.taskStepLimit())
        f.setTaskStepLimit(-5)
        assertNull(f.taskStepLimit())
        f.setTaskStepLimit(FeatureFlags.MAX_TASK_STEP_LIMIT + 100)
        assertEquals(FeatureFlags.MAX_TASK_STEP_LIMIT, f.taskStepLimit())
    }
}
