// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingTest {
    private fun inputs(hasMain: Boolean = false, modelDone: Boolean = false, phoneNeeded: Boolean = true, phoneSeen: Boolean = false) =
        Onboarding.Inputs(hasMain, modelDone, phoneNeeded, phoneSeen)

    @Test fun freshInstallStartsWithWelcomeThenModelThenChat() {
        val i = inputs()
        assertEquals(OnboardingStep.Welcome, Onboarding.start(i))
        assertEquals(OnboardingStep.Model, Onboarding.next(OnboardingStep.Welcome, i))
        assertNull(Onboarding.next(OnboardingStep.Model, i))
        assertNull(Onboarding.next(OnboardingStep.Phone, i))
    }

    @Test fun configuredModelSkipsFirstTwoSteps() {
        assertNull(Onboarding.start(inputs(hasMain = true)))
        assertNull(Onboarding.start(inputs(hasMain = true, phoneSeen = true)))
        assertNull(Onboarding.start(inputs(hasMain = true, phoneNeeded = false)))
    }

    @Test fun modelSetupDoneOnceIsNotRepeatedEvenIfModelsWereRemoved() {
        assertNull(Onboarding.start(inputs(modelDone = true)))
        assertNull(Onboarding.start(inputs(modelDone = true, phoneSeen = true)))
    }

    @Test fun phoneStepSkippedWhenReadyOrSeen() {
        assertNull(Onboarding.next(OnboardingStep.Model, inputs(phoneNeeded = false)))
        assertNull(Onboarding.next(OnboardingStep.Model, inputs(phoneSeen = true)))
    }

    @Test fun dotsFollowSteps() {
        assertEquals(listOf(0, 1), listOf(OnboardingStep.Welcome, OnboardingStep.Model).map(Onboarding::dot))
        assertEquals(2, Onboarding.DOTS)
    }
}
