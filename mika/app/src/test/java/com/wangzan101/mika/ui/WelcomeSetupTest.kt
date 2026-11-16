// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.settings.*
import com.wangzan101.mika.core.model.ModelRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class WelcomeSetupTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun freeStartPersistsCommunityMainAndModelCompletion() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key })
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val state = OnboardingState(prefs)
        state.startCommunity(store)
        assertEquals(ProviderConfig.Kind.Community, store.configFor(ModelRole.Main)?.kind)
        assertEquals("compute", store.configFor(ModelRole.Main)?.model)
        assertTrue(OnboardingState(prefs).modelSetupDone())
        assertNull(Onboarding.start(Onboarding.Inputs(true, state.modelSetupDone(), true, false)))
    }

    @Test fun welcomeUsesCommunityAvailabilityForPrimaryNoticeAndDetails() {
        val source = listOf("src/main/java/com/wangzan101/mika/ui/WelcomeScreen.kt", "app/src/main/java/com/wangzan101/mika/ui/WelcomeScreen.kt")
            .map(::File).first { it.isFile }.readText()
        assertTrue(source.contains("if (community) R.string.t60_start_free else R.string.t60_connect_model"))
        assertTrue(source.contains("onPrimary = if (community) onStart else onOwnModel"))
        assertTrue(source.contains("notice = if (community)"))
        assertTrue(source.contains("details = if (community)"))
        assertFalse(source.contains("referral_prompt"))
    }
}
