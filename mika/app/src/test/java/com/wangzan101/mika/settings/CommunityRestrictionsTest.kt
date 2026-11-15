// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import com.wangzan101.mika.core.run.ComputeMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommunityRestrictionsTest {
    @Test fun communityAndSimulationBlockCallsAndExternalScripts() {
        for (kind in ProviderConfig.Kind.entries) {
            assertEquals(kind == ProviderConfig.Kind.Community,
                CommunityRestrictions.active(kind, ComputeMode.Own))
            assertTrue(CommunityRestrictions.active(kind, ComputeMode.Community))
        }
        assertFalse(CommunityRestrictions.active(null, ComputeMode.Own))
    }

    @Test fun broadcastAdmissionPrecedesConversationAndScheduleWrites() {
        val source = File("src/main/java/com/wangzan101/mika/task/ExternalSchedule.kt").readText()
        val gate = source.indexOf("CommunityRestrictions.active(app)")
        assertTrue(gate > source.indexOf("!FeatureFlags.get(app).externalSchedule()"))
        assertTrue(gate < source.indexOf("val goal ="))
        assertTrue(gate < source.indexOf("ConversationStore.shared(app).newConversation()"))
        assertTrue(gate < source.indexOf("TriggerRuntime.schedule(app, plan)"))
        val flags = File("src/main/java/com/wangzan101/mika/settings/FeatureFlags.kt").readText()
        assertTrue(flags.contains("fun externalSchedule(): Boolean = prefs.getBoolean(EXTERNAL_SCHEDULE, false)"))
    }

    @Test fun callRuntimeChecksAdmissionAndCancelsWhenProviderChanges() {
        val source = File("src/main/java/com/wangzan101/mika/calls/CallRuntime.kt").readText()
        assertTrue(source.contains("communityMode() -> COMMUNITY_UNAVAILABLE"))
        assertTrue(source.contains("if (communityMode()) throw CallProblem(COMMUNITY_UNAVAILABLE)"))
        assertTrue(source.contains("combine(providers.configs, providers.roles, providers.hybridSelected)"))
        assertTrue(source.contains("cancelWork(COMMUNITY_UNAVAILABLE)"))
        assertTrue(source.contains("finishLockedDial(it, COMMUNITY_UNAVAILABLE)"))
        assertTrue(source.contains("if (communityMode()) return@onMain fail(COMMUNITY_UNAVAILABLE)"))
        val ui = File("src/main/java/com/wangzan101/mika/ui/CallSettingsScreen.kt").readText()
        assertTrue(ui.indexOf("if (!available)") < ui.indexOf("CallSettingsWorkspace(runtime"))
        val settings = File("src/main/java/com/wangzan101/mika/ui/SettingsScreen.kt").readText()
        assertTrue(settings.contains("if (callsAvailable)"))
    }
}
