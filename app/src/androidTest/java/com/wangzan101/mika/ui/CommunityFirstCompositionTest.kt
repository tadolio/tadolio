// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder

@RunWith(AndroidJUnit4::class)
class CommunityFirstCompositionTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun entryAppearsWithoutConstructingClientOnFirstComposition() {
        showFirstPage(onboarding = false)
    }

    @Test fun entryAppearsDuringFirstRunWithoutSavedProviders() {
        showFirstPage(onboarding = true)
    }

    private fun showFirstPage(onboarding: Boolean) {
        var constructed = 0
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("No identity needed to render") })
        val ui = ModelSetupUi(AddForm.of(ProviderPresets.all.first()))
        compose.setContent {
            MikaTheme {
                ModelSetupContent(ui, onboarding, ModelSetupActions(
                    communityContent = {
                        CommunityControls(store, enabled = true, clientFactory = { constructed++; error("Must be lazy") })
                    },
                ), onBack = {})
            }
        }
        compose.onNodeWithText("Tadolio 社区算力").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, constructed) }
    }

    @Test fun disabledBuildDoesNotShowEntryOrConstructClient() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("No identity needed to render") })
        compose.setContent {
            MikaTheme { CommunityControls(store, enabled = false, clientFactory = { error("Community is disabled") }) }
        }
        compose.onNodeWithText("Tadolio 社区算力").assertDoesNotExist()
    }
}
