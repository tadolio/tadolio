// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline-compiled UI regressions; no task service, model, microphone or phone tools. */
@RunWith(AndroidJUnit4::class)
class HivoElderUiTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun keyboardSizedViewportKeepsActionVisible() {
        var value by mutableStateOf("hello")
        var bottom by mutableStateOf(0)
        var sent = false
        compose.setContent { MikaTheme {
            Column(Modifier.height(if (bottom == 0) 360.dp else 240.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(160.dp))
                ElderTaskInput(value, { value = it }, { sent = true }, imeBottom = bottom)
            }
        } }
        compose.onNodeWithTag("elder-input").performClick()
        compose.runOnIdle { bottom = 160 }
        compose.waitForIdle()
        compose.onNodeWithTag("elder-send").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(sent) }
    }

    @Test fun wholeModeRowTogglesOnce() {
        var enabled by mutableStateOf(false)
        var changes = 0
        compose.setContent { MikaTheme { ElderModeRow(enabled) { enabled = it; changes++ } } }
        compose.onNodeWithText("长辈模式").performClick()
        compose.runOnIdle { assertTrue(enabled); assertEquals(1, changes) }
        compose.onNodeWithText("长辈模式").performClick()
        compose.runOnIdle { assertFalse(enabled); assertEquals(2, changes) }
    }

    @Test fun statisticsUseProductName() {
        compose.setContent { MikaTheme { Text(stringResource(R.string.elder_estimate, 12, 1)) } }
        compose.onNode(hasText("Tadolio", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("agent", substring = true)).assertDoesNotExist()
    }

    @Test fun recentAnswerRendersMarkdown() {
        compose.setContent { MikaTheme { ElderRecentResult("**Done**") } }
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("**Done**").assertDoesNotExist()
    }

    @Test fun redactionPreviewUsesLocalizedLabels() {
        compose.setContent { MikaTheme { Text(DisplayRedactions.forUi("[redacted:card] [redacted:otp]")) } }
        compose.onNodeWithText("[卡号已隐藏] [验证码已隐藏]").assertIsDisplayed()
        compose.onNode(hasText("redacted:", substring = true)).assertDoesNotExist()
    }

    @Test fun homeExamplesOfferWeatherAndSingleAlarm() {
        compose.setContent { MikaTheme { Column { ChatCopy.examples.forEach { Text(it.text) } } } }
        compose.onNodeWithText("看看明天下不下雨").assertIsDisplayed()
        compose.onNodeWithText("帮我订明天早上 7 点的闹钟").assertIsDisplayed()
        for (word in listOf("王赞", "150", "VIP", "广告"))
            compose.onNode(hasText(word, substring = true)).assertDoesNotExist()
    }
}
