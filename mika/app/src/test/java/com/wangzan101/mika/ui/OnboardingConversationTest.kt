// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OnboardingConversationTest {
    private fun source(name: String) = listOf("src/main/java/com/wangzan101/mika/ui/$name.kt", "app/src/main/java/com/wangzan101/mika/ui/$name.kt")
        .map(::File).first { it.isFile }.readText()

    @Test fun setupChromeHasOnePrimaryAndNoticePrecedesIt() {
        val page = source("OnboardingConversation")
        assertEquals(1, Regex("MikaButton\\(").findAll(page).count())
        assertTrue(page.indexOf("notice?.let") < page.indexOf("primary?.let"))
        assertTrue(page.contains("state.setReadAloud(it)"))
        assertTrue(page.contains("tts.speak(\"\$title\\n\$body\""))
    }

    @Test fun setupScreensDoNotRequestMicrophoneOrRenderHoldToTalk() {
        for (name in listOf("OnboardingConversation", "WelcomeScreen", "GuidedPhoneSetup", "ModelSetupScreen")) {
            val text = source(name)
            assertFalse(text.contains("HoldToTalk"))
            assertFalse(text.contains("RECORD_AUDIO"))
            assertFalse(text.contains("onboardingAnswer"))
        }
    }

    @Test fun guidedSetupWaitsForThePersonAndHasResumeAndTimeoutRecovery() {
        val text = source("GuidedPhoneSetup")
        assertFalse(text.contains("7000"))
        assertFalse(text.contains("1800"))
        assertFalse(text.contains("setupSocialPrompt"))
        assertTrue(text.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(text.contains("accessibilityTimedOut = true"))
        assertTrue(text.contains("if (automatic && wifi && opened != step) openStep(step)"))
        assertTrue(text.contains("GuidedSetup.Step.CodeIntro"))
    }

    @Test fun keyFormHidesCommunityRolesAndPaginationDuringOnboarding() {
        val text = source("ModelSetupScreen").substringAfter("fun ModelSetupContent")
        assertTrue(text.contains("if (!onboarding) actions.communityContent?.invoke()"))
        val onboarding = text.substringAfter("// 上手时先连服务商再选模型").substringAfter("if (onboarding) {").substringBefore("} else {")
        assertTrue(onboarding.contains("ProvidersSection"))
        assertFalse(onboarding.contains("RolesSection"))
        assertFalse(onboarding.contains("ONBOARDING_NOTE"))
        assertFalse(text.contains("ProgressDots("))
    }
}
