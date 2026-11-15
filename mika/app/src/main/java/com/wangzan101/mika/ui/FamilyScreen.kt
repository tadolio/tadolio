// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.HivoConfig
import com.wangzan101.mika.family.*
import com.wangzan101.mika.core.action.FamilyCategory
import com.wangzan101.mika.core.action.FamilyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun FamilyScreen(back: () -> Unit) {
    val context = LocalContext.current
    val client = remember { if (HivoConfig.communityEnabled) FamilyClient.get(context) else null }
    val scope = rememberCoroutineScope()
    var pairs by remember { mutableStateOf(client?.pairs().orEmpty()) }
    var inbox by remember { mutableStateOf(emptyList<JsonObject>()) }
    var code by remember { mutableStateOf("") }
    val namePrefs = remember { context.getSharedPreferences("family_invite_name", android.content.Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(namePrefs.getString("name", "").orEmpty()) }
    var manual by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var voiceTarget by remember { mutableStateOf("") }
    var announcement by remember { mutableStateOf(client?.announcement()) }
    val joinedMessage = stringResource(R.string.family_joined_notice)
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } }
    DisposableEffect(tts) { onDispose { tts.stop(); tts.shutdown() } }
    LaunchedEffect(announcement, ttsReady) {
        val joined = announcement
        if (ttsReady && joined != null && client?.announcementSpoken(joined) == false) {
            if (tts.speak(joinedMessage, TextToSpeech.QUEUE_FLUSH, null, "family-pairing") == TextToSpeech.SUCCESS) client.markAnnouncementSpoken(joined)
        }
    }
    var seen by remember { mutableStateOf(setOf<String>()) }
    var policy by remember { mutableStateOf(client?.policy()) }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try { block(); pairs = client?.pairs().orEmpty() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; pairs = client?.pairs().orEmpty() }
            finally { busy = false }
        }
    }
    val recognition = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if (spoken in setOf("删掉", "删除", "刪掉", "刪除", "remove", "delete", "Remove", "Delete")) {
                pairs.firstOrNull { it.id == voiceTarget }?.let { pair -> work {
                    client?.remove(pair)
                    if (announcement == pair.id) { client?.dismissAnnouncement(); announcement = null }
                } }
            }
        }
    }
    fun listen(target: String) {
        voiceTarget = target
        runCatching { recognition.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
            .onFailure { failed = true }
    }
    LaunchedEffect(client) {
        if (client != null) while (true) {
            if (!busy) work { pairs = client.refresh(); inbox = client.inbox(); client.flushNotices() }
            kotlinx.coroutines.delay(5000)
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(back) { Text(stringResource(R.string.ok_got_it), fontSize = 22.sp) }
        Text(stringResource(R.string.family_title), fontSize = 30.sp)
        Text(stringResource(R.string.family_boundary), fontSize = 22.sp)
        if (policy?.active != true && (policy == null || policy?.mode("scamsms") == FamilyMode.Private)) Text(stringResource(R.string.family_policy_private), fontSize = 26.sp)
        if (client == null) Text(stringResource(R.string.family_unavailable), fontSize = 24.sp)
        else {
            Text(stringResource(R.string.family_remote), fontSize = 24.sp)
            OutlinedTextField(name, { name = it.take(20) }, label = { Text(stringResource(R.string.family_name)) }, modifier = Modifier.fillMaxWidth())
            Button({ work {
                val label = FamilyInviteEntry.name(name)
                check(namePrefs.edit().putString("name", label).commit())
                val invitation = client.invite(label)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, FamilyInviteEntry.share(invitation, label)), null))
            } }, enabled = !busy) { Text(stringResource(R.string.family_create), fontSize = 22.sp) }
            TextButton({ manual = !manual }) { Text(stringResource(R.string.family_received), fontSize = 22.sp) }
            if (manual) {
                OutlinedTextField(code, { code = it.take(16384) }, label = { Text(stringResource(R.string.family_code)) }, modifier = Modifier.fillMaxWidth())
                Button({ pending = FamilyInviteEntry.extract(code) ?: code.trim(); code = "" }, enabled = code.isNotBlank()) {
                    Text(stringResource(R.string.family_open_invite), fontSize = 22.sp)
                }
            }
            pending?.let { value -> FamilyJoinPrompt(value) { pending = null } }
            if (announcement != null) {
                Text(joinedMessage, fontSize = 32.sp)
                TextButton({ client.dismissAnnouncement(); announcement = null }) { Text(stringResource(R.string.ok_got_it), fontSize = 24.sp) }
            }
            if (failed) Text(stringResource(R.string.family_failed), fontSize = 24.sp)
            pairs.forEach { pair ->
                HorizontalDivider()
                Text(client.peerName(pair), fontSize = 24.sp)
                if (pair.complete) Text(stringResource(R.string.family_paired), fontSize = 24.sp)
                else Text(stringResource(R.string.family_wait_join), fontSize = 22.sp)
                TextButton({ work { client.remove(pair); if (announcement == pair.id) { client.dismissAnnouncement(); announcement = null } } }, enabled = !busy) { Text(stringResource(R.string.family_remove), fontSize = 22.sp) }
                TextButton({ listen(pair.id) }, enabled = !busy) { Text(stringResource(R.string.family_say_remove), fontSize = 22.sp) }
            }
            inbox.filter { it.str("id") !in seen }.forEach { message ->
                when (message.str("kind")) {
                    "request" -> {
                        Text(stringResource(R.string.family_request, familyCategory(message.str("category"))), fontSize = 28.sp)
                        Row {
                            Button({ work { client.decide(message, true); seen = seen + message.str("id") } }, enabled = !busy) { Text(stringResource(R.string.family_approve), fontSize = 22.sp) }
                            TextButton({ work { client.decide(message, false); seen = seen + message.str("id") } }, enabled = !busy) { Text(stringResource(R.string.family_deny), fontSize = 22.sp) }
                        }
                    }
                    "notice" -> {
                        val category = message.str("category")
                        val prefix = when {
                            category.startsWith("operation_") -> R.string.family_notice_operation
                            category.startsWith("lowered_") -> R.string.family_notice_lowered
                            else -> null
                        }
                        if (category == "family_added") Text(stringResource(R.string.family_new_notice), fontSize = 28.sp)
                        else if (category == "operation_scamsms") Text(stringResource(R.string.standby_family), fontSize = 26.sp)
                        else if (prefix != null) Text(stringResource(prefix, familyCategory(category.substringAfter('_'))), fontSize = 26.sp)
                    }
                }
            }
            Text(stringResource(R.string.family_policy_local), fontSize = 24.sp)
            FamilyCategory.entries.forEach { category ->
                Text(familyCategory(category.wire), fontSize = 24.sp)
                FamilyMode.entries.forEach { mode ->
                    Row {
                        RadioButton(policy?.mode(category.wire) == mode, onClick = {
                            client.setPolicy(category, mode); policy = client.policy()
                        })
                        TextButton({ client.setPolicy(category, mode); policy = client.policy() }) {
                            Text(stringResource(when (mode) {
                                FamilyMode.Private -> R.string.family_mode_private
                                FamilyMode.Notify -> R.string.family_mode_notify
                                FamilyMode.Approve -> R.string.family_mode_approve
                            }), fontSize = 22.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun familyCategory(category: String) = stringResource(when (category) {
    "scamsms" -> R.string.standby_family
    "pay", "order", "payment" -> R.string.family_payment
    "transfer" -> R.string.family_transfer
    "verification" -> R.string.family_verification
    "security" -> R.string.family_security
    "install" -> R.string.family_install
    "send", "submit" -> R.string.family_send
    "delete" -> R.string.family_delete
    else -> R.string.family_sensitive
})
