// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaIcons

/** First greeting is local and works before a model is configured. */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    var moving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showingNumber by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var inviting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (inviting) { ReferralScreen(back = { inviting = false }); return }
    if (showingNumber) { NumberScreen(back = { showingNumber = false }); return }
    if (moving) { NumberMoveScreen(onTransferred = { moving = false; showingNumber = true }) { moving = false }; return }
    OnboardingConversation(androidx.compose.ui.res.stringResource(R.string.onboarding_greeting),
        onReply = { if (onboardingAnswer(it) == OnboardingAnswer.Start) onStart() }) {
        androidx.compose.material3.TextButton(onClick = { moving = true }) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.t42_have_number))
        }
        MikaButton(WelcomeCopy.START, onStart, Modifier.fillMaxWidth().testTag("welcome-start"))
        androidx.compose.material3.TextButton(onClick = { inviting = true }) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.referral_prompt))
        }
    }
}

/** 欢迎页的文字。 */
object WelcomeCopy {
    val TITLE: String get() = tr(R.string.welcome_title)
    val LEAD: String get() = tr(R.string.welcome_lead)
    val NOTE: String get() = tr(R.string.welcome_note)
    val START: String get() = tr(R.string.welcome_start)

    data class Item(val icon: ImageVector, val title: String, val body: String)

    val FEATURES get() = listOf(
        Item(MikaIcons.Apps, tr(R.string.welcome_apps), tr(R.string.welcome_apps_body)),
        Item(MikaIcons.Globe, tr(R.string.welcome_web), tr(R.string.welcome_web_body)),
        *(if (com.wangzan101.mika.BuildConfig.SANDBOX_ENABLED) arrayOf(Item(MikaIcons.Doc, tr(R.string.welcome_files), tr(R.string.welcome_files_body))) else emptyArray()),
        Item(MikaIcons.Clock, tr(R.string.welcome_schedule), tr(R.string.welcome_schedule_body)),
    )
}
