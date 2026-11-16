// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.ui.theme.MikaIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.voice.SystemSpeech
import java.util.Locale

/** Local setup chrome. Only the title and body are spoken. */
@Composable
internal fun SetupPage(
    title: String,
    body: String,
    primary: String? = null,
    onPrimary: () -> Unit = {},
    notice: String? = null,
    steps: String? = null,
    detailsLabel: String? = null,
    details: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
    links: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val state = remember { OnboardingState(context) }
    var readAloud by remember { mutableStateOf(state.readAloud()) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var expanded by remember(title) { mutableStateOf(false) }
    val tts = remember { TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS } }
    LaunchedEffect(title, body, readAloud, ready) {
        tts.stop()
        if (readAloud && ready) {
            val result = tts.setLanguage(Locale.forLanguageTag(SystemSpeech.speechTag(AppLanguage.current(context))))
            failed = result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED
            if (!failed) failed = tts.speak("$title\n$body", TextToSpeech.QUEUE_FLUSH, null, "setup") == TextToSpeech.ERROR
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { tts.stop() }
    DisposableEffect(tts) { onDispose { tts.shutdown() } }
    PageBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            IconToggleButton(readAloud, { readAloud = it; state.setReadAloud(it) }, Modifier.align(Alignment.End).testTag("setup-read-aloud")) {
                Icon(if (readAloud) MikaIcons.Sound else MikaIcons.Mute,
                    stringResource(R.string.onboarding_read_aloud))
            }
            Text(title, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("setup-title"))
            Text(body, fontSize = 18.sp, lineHeight = 27.sp, modifier = Modifier.testTag("setup-body"))
            if (readAloud && failed) Text(stringResource(R.string.onboarding_tts_missing))
            steps?.let { Text(it, fontSize = 14.sp, modifier = Modifier.testTag("setup-steps")) }
            content()
            Spacer(Modifier.height(32.dp))
            notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.testTag("setup-notice")) }
            primary?.let { MikaButton(it, onPrimary, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-primary")) }
            links()
            if (details != null) TextButton({ expanded = true }) { Text(detailsLabel ?: stringResource(R.string.t60_why)) }
        }
    }
    if (expanded && details != null) SetupDetails({ expanded = false }) { details { expanded = false } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupDetails(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
