// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.number.NumberClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun ReferralScreen(initialInviter: String = "", back: () -> Unit) {
    val client = NumberClient.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var inviter by rememberSaveable(initialInviter) { mutableStateOf(initialInviter) }
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    suspend fun refresh() {
        if (client.mine().isNotEmpty()) status = client.referralStatus()
        loaded = true
    }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { work { refresh() } }
    BackHandler { back() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = back) { Text(stringResource(R.string.number_back)) }
            Text(stringResource(R.string.referral_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.referral_intro))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { result ->
                Text(stringResource(R.string.referral_counts,
                    result["total"]?.jsonPrimitive?.intOrNull ?: 0,
                    result["counted"]?.jsonPrimitive?.intOrNull ?: 0))
                result["invitees"]?.jsonArray?.forEach { item ->
                    val entry = item.jsonObject
                    Text(entry["number"]!!.jsonPrimitive.content)
                }
                result["binding"]?.takeIf { it != JsonNull }?.jsonObject?.let {
                    Text(stringResource(R.string.referral_bound, it["inviter"]!!.jsonPrimitive.content))
                }
            }
            val canBind = loaded && (status?.get("canBind")?.jsonPrimitive?.booleanOrNull ?: (client.mine().isEmpty()))
            if (canBind) {
                OutlinedTextField(value = inviter, onValueChange = { inviter = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.referral_prompt)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.referral_disclosure))
                Button(enabled = !busy && Regex("[0-9]{8}").matches(inviter), onClick = { confirming = true }) {
                    Text(stringResource(R.string.referral_confirm))
                }
            } else if (loaded && status?.get("binding") == JsonNull) Text(stringResource(R.string.referral_closed))
            if (failed) {
                Text(stringResource(R.string.referral_error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { work { refresh() } }) { Text(stringResource(R.string.referral_retry)) }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { if (!busy) confirming = false },
        title = { Text(stringResource(R.string.referral_confirm_number, inviter)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.referral_disclosure))
            if (failed) Text(stringResource(R.string.referral_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            work {
                client.bindInviter(inviter)
                confirming = false
                refresh()
            }
        }) { Text(stringResource(R.string.referral_confirm)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirming = false }) { Text(stringResource(R.string.referral_skip)) } })
}
