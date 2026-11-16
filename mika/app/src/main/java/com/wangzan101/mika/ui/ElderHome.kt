// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.wangzan101.mika.R
import com.wangzan101.mika.family.ElderStore
import com.wangzan101.mika.settings.Prerequisites

@Composable
internal fun ElderHome(vm: ChatViewModel, check: Prerequisites.Check, settings: () -> Unit, voice: com.wangzan101.mika.voice.VoiceSource? = null, phoneSetupNeeded: Boolean = false, openPhoneSetup: () -> Unit = {}, details: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ElderStore.get(context) }
    val revision by store.revision.collectAsState()
    val waitingFamily by com.wangzan101.mika.family.FamilyRunApproval.waiting.collectAsState()
    val ui by vm.ui.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val hold = remember { com.wangzan101.mika.voice.HoldToTalk(android.os.SystemClock::elapsedRealtime) }
    val speech by hold.state.collectAsState()
    val listening = speech is com.wangzan101.mika.voice.HoldToTalk.State.Listening
    val scope = rememberCoroutineScope()
    var speechJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val error = stringResource(R.string.elder_voice_error)
    val missing = stringResource(R.string.elder_voice_missing)
    val voiceLabel = stringResource(R.string.elder_hold)
    fun startSpeech() {
        if (voice == null) { note = missing; return }
        note = null
        speechJob?.cancel()
        val session = voice.start()
        hold.press(session)
        speechJob = scope.launch { session.events.collect(hold::onEvent) }
    }
    LaunchedEffect(speech) {
        when (val current = speech) {
            is com.wangzan101.mika.voice.HoldToTalk.State.Done -> { text = current.text; hold.reset() }
            is com.wangzan101.mika.voice.HoldToTalk.State.Problem -> { note = error; hold.reset() }
            else -> Unit
        }
    }
    LaunchedEffect(listening) {
        while (listening) { kotlinx.coroutines.delay(100); hold.tick() }
    }
    DisposableEffect(hold) { onDispose { speechJob?.cancel(); hold.dispose() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A new explicit hold starts recording; a permission dialog never starts the microphone.
        if (!granted) note = error
    }
    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.elder_home_title), Modifier.weight(1f), fontSize = 30.sp, color = Color.Black)
            OutlinedButton(settings) { Text(stringResource(R.string.settings_title), fontSize = 20.sp, color = Color.Black) }
        }
        Text(stringResource(R.string.elder_home_note), fontSize = 20.sp, color = Color.Gray,
            modifier = Modifier.fillMaxWidth().clickable(onClick = settings))
        Surface(color = Color(0xff103c20), contentColor = Color.White,
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).semantics {
                this.role = Role.Button
                onClick(label = voiceLabel) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    else if (listening) hold.release() else startSpeech()
                    true
                }
            }.pointerInput(voice) {
                detectTapGestures(onPress = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    else {
                        startSpeech()
                        if (tryAwaitRelease()) hold.release() else hold.dispose()
                    }
                })
            }) {
            Box(Modifier.padding(24.dp)) { Text(stringResource(if (listening) R.string.elder_listening else R.string.elder_hold), fontSize = 28.sp) }
        }
        if (voice == null) Text(missing, color = Color.Black, fontSize = 22.sp)
        note?.let { Text(it, color = Color(0xff8b0000), fontSize = 22.sp) }
        ElderTaskInput(text, { text = it }, { if (vm.send(text, check)) text = "" })
        DeferredSetupPrompts(ui)
        if (phoneSetupNeeded && ui.exchanges.isNotEmpty()) PhoneSetupLine(openPhoneSetup)
        ui.blocked?.let { TextButton(settings) { Text(stringResource(R.string.chat_go_settings), fontSize = 22.sp) } }
        ui.exchanges.lastOrNull()?.takeIf { it.status !is ExchangeStatus.Done && it.status !is ExchangeStatus.Failed && it.status !is ExchangeStatus.Cancelled }?.let { Text(ChatCopy.progressLine(it), color = Color.Black, fontSize = 24.sp) }
        if (waitingFamily) Text(stringResource(R.string.family_waiting), fontSize = 28.sp, color = Color.Black)
        TextButton(details) { Text(stringResource(R.string.elder_details), fontSize = 22.sp) }
        val tick by produceState(System.currentTimeMillis()) {
            while (true) { kotlinx.coroutines.delay(60000); value = System.currentTimeMillis() }
        }
        val tasks = remember(revision, tick) { store.weekTasks() }
        Text(stringResource(R.string.elder_estimate, tasks), fontSize = 22.sp, color = Color.Black)
        Text(stringResource(R.string.elder_recent), fontSize = 26.sp, color = Color.Black)
        remember(revision) { store.recent() }.forEach { ElderRecentResult(it) }
    }
}
