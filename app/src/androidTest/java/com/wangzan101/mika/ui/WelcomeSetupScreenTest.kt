// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeSetupScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun enabledCommunityHasOnePrimaryWithVisibleDisclosureBeforeIt() {
        var started = false
        compose.setContent { MikaTheme { WelcomeContent(true, { started = true }, {}, {}, {}) } }
        compose.onAllNodesWithTag("setup-primary").assertCountEquals(1)
        val primary = compose.onNodeWithTag("setup-primary").performScrollTo().fetchSemanticsNode().boundsInRoot
        val notice = compose.onNodeWithTag("setup-notice").fetchSemanticsNode().boundsInRoot
        assertTrue(notice.bottom <= primary.top)
        compose.onNodeWithText("按住说话").assertDoesNotExist()
        compose.onNodeWithTag("setup-primary").performClick()
        assertTrue(started)
    }

    @Test fun disabledCommunityConnectsOwnModelAndHidesDisclosureAndFreeDetails() {
        var own = false
        compose.setContent { MikaTheme { WelcomeContent(false, {}, { own = true }, {}, {}) } }
        compose.onAllNodesWithTag("setup-primary").assertCountEquals(1)
        compose.onNodeWithTag("setup-notice").assertDoesNotExist()
        compose.onNodeWithText("免费是怎么回事？").assertDoesNotExist()
        compose.onNodeWithText("连接模型").performScrollTo().performClick()
        assertTrue(own)
    }
}
