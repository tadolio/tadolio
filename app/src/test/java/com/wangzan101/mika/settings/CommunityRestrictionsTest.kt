// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import com.wangzan101.mika.core.run.ComputeMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommunityRestrictionsTest {
    @Test fun computeModesDoNotRestrictCallsOrExternalScripts() {
        for (kind in ProviderConfig.Kind.entries) for (mode in ComputeMode.entries) {
            assertFalse(CommunityRestrictions.active(kind, mode))
        }
        assertFalse(CommunityRestrictions.active(null, ComputeMode.Own))
    }

    @Test fun broadcastRetainsUserSwitchAndValidationBeforeWrites() {
        val source = File("src/main/java/com/wangzan101/mika/task/ExternalSchedule.kt").readText()
        assertFalse(source.contains("CommunityRestrictions"))
        val gate = source.indexOf("!FeatureFlags.get(app).externalSchedule()")
        assertTrue(gate >= 0)
        assertTrue(gate < source.indexOf("ConversationStore.shared(app).newConversation()"))
        assertTrue(source.contains("if (goal.length > MAX_PROMPT)"))
        assertTrue(source.contains("if (!allowNow(prefs))"))
    }

    @Test fun callAdmissionRetainsDeviceAndCredentialChecksForAllModes() {
        val source = File("src/main/java/com/wangzan101/mika/calls/CallRuntime.kt").readText()
        assertFalse(source.contains("COMMUNITY_UNAVAILABLE"))
        assertFalse(source.contains("communityMode()"))
        assertTrue(source.contains("!armed || !access.hasPhonePermissions()"))
        assertTrue(source.contains("!activeVoiceProfile().credentialsConfigured"))
    }
}
