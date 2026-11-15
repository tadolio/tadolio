// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.family.str
import com.wangzan101.mika.number.NumberClient
import com.wangzan101.mika.number.NumberMoveCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Explicit, in-page handoff. Only the system credential sheet interrupts the page. */
@Composable
internal fun NumberMoveScreen(initialPage: String = "receive", initialCode: String = "", onTransferred: (() -> Unit)? = null, back: () -> Unit) {
    val context = LocalContext.current
    val client = remember { NumberClient.get(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(initialPage) }
    var busy by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(client.moved()) }
    var failed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(initialCode) }
    var recoveryInput by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var transfer by remember { mutableStateOf<JsonObject?>(null) }
    var pending by remember { mutableStateOf<JsonArray>(JsonArray(emptyList())) }
    var recovery by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(client.moves.locallySaved()) }
    var seconds by remember { mutableLongStateOf(0) }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { moved = client.moved(); busy = false }
        }
    }
    val unlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) work { recovery = client.moves.code(); page = "recovery" }
    }
    fun viewRecovery() {
        work {
            client.moves.ensureRecovery()
            if (!client.moves.viewed()) recovery = client.moves.code()
            else {
                val manager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val intent = manager.createConfirmDeviceCredentialIntent(context.getString(R.string.move_unlock), null)
                if (intent != null) unlock.launch(intent) else status = "unlock_required"
            }
        }
    }
    androidx.activity.compose.BackHandler { recovery = ""; if (page == "restore") page = "receive" else back() }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        onPauseOrDispose { recovery = "" }
    }
    LaunchedEffect(page) {
        if (page == "complete") { delay(1200); (onTransferred ?: back).invoke() }
    }
    LaunchedEffect(Unit) {
        if (!moved && initialPage == "transfer") work {
            pending = client.moves.pending()
            if (pending.isEmpty()) transfer = client.moves.create()
        }
        if (initialPage == "recovery") viewRecovery()
        if (client.mine().isNotEmpty()) runCatching { saved = client.moves.status()["saved"]?.jsonPrimitive?.boolean == true }
        while (true) {
            if (!busy && !client.moved() && client.mine().isNotEmpty()) runCatching { pending = client.moves.pending() }
            if (!busy && client.moves.recovering()) runCatching { client.moves.resumeRecovery(); page = "complete" }
            if (!busy && client.moves.receiving() && !client.moves.recovering()) runCatching {
                status = client.moves.finishTransfer().orEmpty()
                if (status == "completed") page = "complete"
            }
            moved = client.moved()
            seconds = ((transfer?.get("expires")?.jsonPrimitive?.longOrNull ?: 0) - System.currentTimeMillis()).coerceAtLeast(0) / 1000
            delay(2000)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { recovery = ""; if (page == "restore") page = "receive" else back() }) { Text(stringResource(R.string.number_back)) }
            Text(stringResource(when (page) {
                "transfer" -> R.string.t42_to_phone
                "restore" -> R.string.t42_restore_title
                "recovery" -> R.string.t42_recovery
                else -> R.string.t42_receive_title
            }),style = MaterialTheme.typography.headlineSmall)
            if (moved) {
                Text(stringResource(if (client.movedByRecovery()) R.string.move_recovered_elsewhere else R.string.move_moved))
                Button(enabled = !busy,onClick = { work { client.claim();back() } }) { Text(stringResource(R.string.move_new_number)) }
            }
            if (failed) Text(stringResource(R.string.move_error),color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!moved) when (page) {
                "receive" -> {
                    OutlinedTextField(value = code, enabled = !busy && !client.moves.receiving(), onValueChange = { code = it.filter(Char::isDigit).take(8) },
                        label = { Text(stringResource(R.string.t42_transfer_code)) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        isError = code.length == 8 && !NumberMoveCodes.transferValid(code))
                    if (client.mine().isNotEmpty()) Text(stringResource(R.string.t42_replace_number, NumberChatState.formatted(client.mine())))
                    Button(enabled = !busy && !client.moves.receiving() && NumberMoveCodes.transferValid(code), onClick = { work { client.moves.request(code); status = "pending" } }) {
                        Text(stringResource(R.string.t42_receive))
                    }
                    Text(stringResource(R.string.t42_old_phone_hint), style = MaterialTheme.typography.bodySmall)
                    if (status == "pending" || client.moves.receiving()) Text(stringResource(R.string.move_wait_confirmation))
                    if (status in listOf("rejected", "expired", "cancelled")) Text(stringResource(R.string.move_transfer_ended))

                    TextButton(enabled = !busy && !client.moves.receiving(), onClick = { page = "restore" }) { Text(stringResource(R.string.t42_lost_phone)) }
                }
                "restore" -> {
                    OutlinedTextField(value = number, onValueChange = { number = it.filter(Char::isDigit).take(8) },
                        label = { Text(stringResource(R.string.number_title)) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    OutlinedTextField(value = recoveryInput, onValueChange = { recoveryInput = NumberMoveCodes.normalize(it).take(24) },
                        label = { Text(stringResource(R.string.t42_recovery_code)) }, singleLine = true)
                    if (client.mine().isNotEmpty()) Text(stringResource(R.string.t42_replace_number, NumberChatState.formatted(client.mine())))
                    Button(enabled = !busy && number.length == 8 && NumberMoveCodes.recoveryValid(recoveryInput), onClick = {
                        work { client.moves.restore(number, recoveryInput); recoveryInput = ""; page = "complete" }
                    }) { Text(stringResource(R.string.t42_restore)) }
                }
                "transfer" -> {
                    transfer?.let {
                        // The 8-digit code looks like a Tadolio number, so name both explicitly (users mistook it for a new number).
                        if (client.mine().isNotEmpty()) Text(stringResource(R.string.t42_moving_number, NumberChatState.formatted(client.mine())))
                        Text(stringResource(R.string.t42_transfer_intro))
                        Text(stringResource(R.string.t42_transfer_code_label), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(it.str("code").chunked(4).joinToString("-"),style = MaterialTheme.typography.headlineMedium) }
                        QrCodeImage("tadolio-transfer:${it.str("code")}",stringResource(R.string.move_code),Modifier.size(220.dp))
                        Text(stringResource(R.string.move_expires,seconds))
                    }
                    Text(stringResource(R.string.t42_old_warning))
                }
                "recovery" -> {
                    if (status == "unlock_required") Text(stringResource(R.string.move_unlock_required))
                    if (recovery.isEmpty()) TextButton(enabled = !busy, onClick = ::viewRecovery) { Text(stringResource(R.string.move_unlock)) }
                    Text(stringResource(R.string.move_recovery_warning))
                    SelectionContainer { Text(recovery,style = MaterialTheme.typography.headlineMedium) }
                    Button(enabled = !busy && recovery.isNotEmpty(),onClick = { work { client.moves.saved();saved = true;recovery = "";back() } }) { Text(stringResource(R.string.move_saved)) }
                    TextButton(enabled = !busy && recovery.isNotEmpty(),onClick = { work { client.moves.ensureRecovery(true);recovery = client.moves.code();saved = false } }) { Text(stringResource(R.string.move_regenerate)) }
                }
                "complete" -> { Text(stringResource(R.string.t42_complete));TextButton(onClick = { (onTransferred ?: back).invoke() }) { Text(stringResource(R.string.number_back)) } }
            }
            pending.forEach { item ->
                val row = item.jsonObject
                Text(stringResource(R.string.move_confirm,row.str("number"),row.str("model")))
                Row {
                    Button(enabled = !busy,onClick = { work { client.moves.decide(row,true);pending = JsonArray(emptyList()) } }) { Text(stringResource(R.string.move_accept)) }
                    TextButton(enabled = !busy,onClick = { work { client.moves.decide(row,false);pending = client.moves.pending();transfer = client.moves.create() } }) { Text(stringResource(R.string.move_reject)) }
                }
            }
        }
    }
}

@Composable
internal fun NumberMoveEntry(open: (String) -> Unit) {
    val client = NumberClient.get(LocalContext.current)
    var saved by remember { mutableStateOf(client.moves.locallySaved()) }
    var moved by remember { mutableStateOf(client.moved()) }
    LaunchedEffect(Unit) {
        while (true) {
            moved = client.moved()
            if (!moved && client.mine().isNotEmpty()) runCatching {
                saved = client.moves.status()["saved"]?.jsonPrimitive?.boolean == true
            }
            delay(5000)
        }
    }
    if (moved) {
        TextButton(onClick = { open("receive") }) { Text(stringResource(if (client.movedByRecovery()) R.string.move_recovered_elsewhere else R.string.move_moved)) }
    } else Column {
        TextButton(onClick = { open("scan") }) { Text(stringResource(R.string.t42_scan_number)) }
        TextButton(onClick = { open("transfer") }) { Text(stringResource(R.string.t42_to_phone)) }
        TextButton(onClick = { open("recovery") }) {
            Text(stringResource(R.string.t42_recovery), Modifier.weight(1f))
            Text(stringResource(if (saved) R.string.t42_saved else R.string.t42_unsaved),
                color = if (saved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }
}
