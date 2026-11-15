// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.voice.SystemSpeechSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Local conversation chrome. It has no model, task, screen or network port. */
@Composable
internal fun OnboardingConversation(
    message: String,
    onReply: (String) -> Unit,
    choices: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val state = remember { OnboardingState(context) }
    val scope = rememberCoroutineScope()
    var readAloud by remember { mutableStateOf(state.readAloud()) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsFailed by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            ttsFailed = !ttsReady
        }
    }
    val hold = remember { HoldToTalk(SystemClock::elapsedRealtime) }
    val speech by hold.state.collectAsState()
    val listening = speech is HoldToTalk.State.Listening
    var job by remember { mutableStateOf<Job?>(null) }
    var speechFailed by remember { mutableStateOf(false) }
    val reply = rememberUpdatedState(onReply)
    val capability = remember(context) { SystemSpeech.capability(context) }
    val label = stringResource(R.string.elder_hold)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        speechFailed = !granted
    }
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!capability.available) { speechFailed = true; return }
        tts.stop()
        speechFailed = false
        job?.cancel()
        val session = SystemSpeechSession(context, capability.onDevice,
            SystemSpeech.speechTag(AppLanguage.current(context)))
        hold.press(session)
        job = scope.launch { session.events.collect(hold::onEvent) }
    }
    LaunchedEffect(message, readAloud, ttsReady) {
        if (!readAloud) { tts.stop(); return@LaunchedEffect }
        if (ttsReady) {
            val tag = SystemSpeech.speechTag(AppLanguage.current(context))
            val language = tts.setLanguage(Locale.forLanguageTag(tag))
            ttsFailed = language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsFailed) ttsFailed = tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "onboarding") == TextToSpeech.ERROR
        }
    }
    LaunchedEffect(speech) {
        when (val result = speech) {
            is HoldToTalk.State.Done -> { hold.reset(); reply.value(result.text) }
            is HoldToTalk.State.Problem -> { speechFailed = true; hold.reset() }
            else -> Unit
        }
    }
    LaunchedEffect(listening) { while (listening) { delay(100); hold.tick() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { tts.stop(); job?.cancel(); hold.dispose() }
    DisposableEffect(tts, hold) {
        onDispose { tts.shutdown(); job?.cancel(); hold.dispose() }
    }
    PageBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(message, fontSize = 28.sp, lineHeight = 40.sp, modifier = Modifier.testTag("onboarding-message"))
            Row {
                Switch(readAloud, {
                    readAloud = it
                    state.setReadAloud(it)
                })
                Text(stringResource(R.string.onboarding_read_aloud), Modifier.padding(12.dp), fontSize = 20.sp)
            }
            if (readAloud && ttsFailed) Text(stringResource(R.string.onboarding_tts_missing), fontSize = 20.sp)
            choices()
            Surface(color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).testTag("onboarding-hold").semantics {
                    role = Role.Button
                    onClick(label = label) { if (listening) hold.release() else start(); true }
                }.pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        start()
                        if (tryAwaitRelease()) hold.release() else hold.dispose()
                    })
                }) {
                Text(stringResource(if (listening) R.string.elder_listening else R.string.elder_hold),
                    Modifier.padding(20.dp), fontSize = 24.sp)
            }
            if (speechFailed) Text(stringResource(R.string.elder_voice_error), fontSize = 20.sp)
        }
    }
}

internal enum class OnboardingAnswer { Start, Community, Account, ApiKey }

/** Exact local choices only. Recognized text never becomes a task or setting write. */
internal fun onboardingAnswer(text: String): OnboardingAnswer? = when (text.trim().lowercase(Locale.ROOT)) {
    "开始", "開始", "好", "好的", "start" -> OnboardingAnswer.Start
    "免费", "免費", "社区", "社區", "免费算力", "免費算力", "community", "free" -> OnboardingAnswer.Community
    "登录", "登入", "账号登录", "帳號登入", "account", "sign in" -> OnboardingAnswer.Account
    "api key", "key", "我有api key", "我有 api key", "密钥", "金鑰" -> OnboardingAnswer.ApiKey
    else -> null
}
