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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wangzan101.mika.R
import com.wangzan101.mika.about.HivoConfig
import com.wangzan101.mika.family.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Shared by home clipboard, deep links, deliberate shares and manual paste. */
@Composable
internal fun FamilyJoinPrompt(code: String, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var invitation by remember(code) { mutableStateOf<JsonObject?>(null) }
    val name = FamilyInviteEntry.name(invitation?.get("name")?.jsonPrimitive?.content.orEmpty())
    var busy by remember(code) { mutableStateOf(false) }
    var failed by remember(code) { mutableStateOf(false) }
    var loading by remember(code) { mutableStateOf(true) }
    LaunchedEffect(code) {
        try {
            invitation = FamilyClient.get(context).resolve(code)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    var joined by remember(code) { mutableStateOf(false) }
    val message = stringResource(when {
        joined -> R.string.family_joined_named
        failed -> R.string.family_invite_failed
        else -> R.string.family_question_named
    }, name)
    var ready by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS } }
    DisposableEffect(tts) { onDispose { tts.stop(); tts.shutdown() } }
    LaunchedEffect(ready, message, loading) { if (ready && !loading) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "family-invitation") }
    fun join() {
        if (busy || failed || joined || loading) return
        busy = true
        scope.launch {
            try {
                check(HivoConfig.communityEnabled)
                val client = FamilyClient.get(context)
                client.join(checkNotNull(invitation))
                client.announcement()?.let { client.markAnnouncementSpoken(it) }
                client.dismissAnnouncement()
                joined = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    val recognition = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.lowercase()
            if (words in setOf("加", "加入", "加家人", "add", "join")) join()
            else if (words in setOf("不加", "不要", "no")) dismiss()
        }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (loading) CircularProgressIndicator() else Text(message, fontSize = 32.sp, lineHeight = 44.sp)
                if (!joined && !failed && !loading) {
                    Text(stringResource(R.string.family_stranger), fontSize = 16.sp)
                    Button(onClick = { join() }, enabled = !busy) { Text(stringResource(R.string.family_join), fontSize = 28.sp) }
                    TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.family_no), fontSize = 28.sp) }
                    TextButton(onClick = {
                        runCatching { recognition.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
                    }, enabled = !busy) { Text(stringResource(R.string.family_say_add), fontSize = 24.sp) }
                } else TextButton(onClick = dismiss) { Text(stringResource(R.string.ok_got_it), fontSize = 26.sp) }
            }
        }
    }
}
