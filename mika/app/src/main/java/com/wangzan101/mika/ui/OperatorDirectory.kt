// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.community.CommunityClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal fun operatorErrorText(error: Exception): Int = when (error.message) {
    "community_invite_not_found" -> R.string.operator_invite_not_found
    "community_operator_full" -> R.string.operator_full_error
    "community_operator_unavailable" -> R.string.operator_unavailable_error
    else -> R.string.community_unavailable
}

internal fun operatorLookupText(invite: String, directory: Boolean): Int =
    if (invite.isNotBlank()) R.string.operator_use_invite else if (directory) R.string.operator_refresh else R.string.operator_directory

internal fun JsonObject.operatorText(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
internal fun JsonObject.operatorFlag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

@Composable internal fun OperatorDirectory(client: CommunityClient, initialInvite: String = "", current: Boolean = false, used: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var invite by remember { mutableStateOf(initialInvite.uppercase(java.util.Locale.ROOT)) }
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    var reporting by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var reported by remember { mutableStateOf(false) }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = operatorErrorText(e) }
            finally { busy = false }
        }
    }
    suspend fun load(more: Boolean = false) {
        if (invite.isNotBlank()) {
            selected = withContext(Dispatchers.IO) { client.preview(invite.trim()) }
        } else {
            val page = withContext(Dispatchers.IO) { client.operators(if (more) cursor else null) }
            val next = page["items"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            items = if (more) items + next else next
            cursor = page["cursor"]?.jsonPrimitive?.contentOrNull
        }
        loaded = true
    }
    LaunchedEffect(Unit) {
        work {
            if (current) {
                val bound = withContext(Dispatchers.IO) { client.binding() }
                if (bound.operatorText("nodeId").isNotBlank()) selected = bound else load()
            } else load()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = close) { Text(stringResource(R.string.number_back)) }
                Text(stringResource(R.string.operator_directory), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.operator_explanation))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    failure?.let { Text(stringResource(it)) }
                    val selection = selected
                    if (selection == null) {
                        OutlinedTextField(
                            value = invite,
                            onValueChange = { invite = it.uppercase(java.util.Locale.ROOT) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { work { load() } }),
                            label = { Text(stringResource(R.string.operator_invite)) },
                            enabled = !busy,
                        )
                        TextButton(onClick = { work { load() } }, enabled = !busy) { Text(stringResource(operatorLookupText(invite, true))) }
                        if (loaded && items.isEmpty()) Text(stringResource(R.string.operator_empty))
                        items.forEach { item ->
                            OutlinedButton(modifier = Modifier.fillMaxWidth().alpha(if (item.operatorFlag("full")) 0.45f else 1f), enabled = !busy && !item.operatorFlag("full"), onClick = { work {
                                selected = withContext(Dispatchers.IO) { client.previewNode(item.operatorText("nodeId")) }
                                reported = false
                            } }) { Column(Modifier.fillMaxWidth()) { OperatorDetails(item) } }
                        }
                        if (cursor != null) TextButton(enabled = !busy, onClick = { work { load(true) } }) { Text(stringResource(R.string.operator_more)) }
                    } else {
                        val card = selection["card"]?.jsonObject ?: buildJsonObject {}
                        val detail = selection["operator"]?.jsonObject ?: card
                        OperatorDetails(detail)
                        if (card.operatorText("contact").isNotBlank()) Text(card.operatorText("contact"))
                        Text(stringResource(R.string.coins_provider_share, card["share"]?.jsonPrimitive?.intOrNull ?: 0))
                        Text(stringResource(R.string.operator_share_rule))
                        Text(stringResource(R.string.community_binding_contact))
                        card.operatorText("number").takeIf { it.matches(Regex("[0-9]{8}")) }?.let { number ->
                            TextButton(enabled = !busy, onClick = { work {
                                com.wangzan101.mika.number.NumberClient.get(context).block(number, true)
                                withContext(Dispatchers.IO) { client.unbind() }
                                close()
                            } }) { Text(stringResource(R.string.number_block)) }
                        }
                        if (reporting) {
                            OutlinedTextField(value = reason, onValueChange = { reason = it.take(200) }, label = { Text(stringResource(R.string.operator_reason)) })
                            TextButton(enabled = !busy && !reported, onClick = { work {
                                withContext(Dispatchers.IO) { client.report(selection.operatorText("nodeId"), reason) }
                                reported = true; reporting = false
                            } }) { Text(stringResource(R.string.operator_report)) }
                        } else TextButton(enabled = !busy && !reported, onClick = { reporting = true }) { Text(stringResource(R.string.operator_report)) }
                        if (reported) Text(stringResource(R.string.operator_reported))
                        TextButton(onClick = { selected = null; reporting = false }) { Text(stringResource(R.string.number_back)) }
                    }
                }
                selected?.let { selection ->
                    TextButton(enabled = !busy && (current || selection["operator"]?.jsonObject?.operatorFlag("full") != true), onClick = { work {
                        val share = selection["card"]?.jsonObject?.get("share")?.jsonPrimitive?.intOrNull ?: 0
                        withContext(Dispatchers.IO) { client.bind(selection.operatorText("nodeId"), share) }
                        used(); close()
                    } }) { Text(stringResource(R.string.operator_use)) }
                }
            }
        }
    }
}

@Composable private fun OperatorDetails(card: JsonObject) {
    Text(card.operatorText("name"), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.operator_share, card["share"]?.jsonPrimitive?.intOrNull ?: 0), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    Text(card.operatorText("intro"))
    Text(card.operatorText("platform") + " · " + card.operatorText("model"))
    Text(stringResource(if (card.operatorFlag("full")) R.string.operator_full else if (card.operatorFlag("online")) R.string.operator_online else R.string.operator_offline))
    Text(stringResource(R.string.operator_users, card.operatorText("users")))
    if (card.operatorFlag("official")) Text(stringResource(R.string.operator_official))
}
