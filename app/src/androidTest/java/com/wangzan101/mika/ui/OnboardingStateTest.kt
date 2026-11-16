// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class OnboardingStateTest {
    @Test fun readingPreferencePersistsAndDoesNotFinishModelSetup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
        val keys = listOf("read_aloud", "model_setup_done")
        val previous = keys.associateWith { key -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null }
        try {
            prefs.edit().remove("read_aloud").remove("model_setup_done").commit()
            val first = OnboardingState(context)
            assertTrue(first.readAloud())
            first.setReadAloud(false)
            val restored = OnboardingState(context)
            assertFalse(restored.readAloud())
            assertFalse(restored.modelSetupDone())
            restored.markModelSetupDone()
            assertFalse(OnboardingState(context).readAloud())
            assertTrue(OnboardingState(context).modelSetupDone())
        } finally {
            val editor = prefs.edit()
            previous.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putBoolean(key, value) }
            editor.commit()
        }
    }
}
