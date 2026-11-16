// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** First-run community choice must lead with one obvious button; invitations stay collapsed. */
class CommunityOnboardingTest {
    private val source = listOf("src/main/java/com/wangzan101/mika/ui/CommunityControls.kt",
        "app/src/main/java/com/wangzan101/mika/ui/CommunityControls.kt").map(::File).first { it.isFile }.readText()

    @Test fun onboardingContainsOnlyProviderInvitationAndNoSecondFreeChoice() {
        val onboarding = source.substringAfter("if (onboarding) {").substringBefore("        return\n    }")
        assertTrue(onboarding.contains("OutlinedTextField"))
        assertTrue(onboarding.contains("operator_invite"))
        assertTrue(!onboarding.contains("community_start_free"))
        assertTrue(!onboarding.contains("community_privacy"))
        assertTrue(!onboarding.contains("community_miner_link"))
    }
}
