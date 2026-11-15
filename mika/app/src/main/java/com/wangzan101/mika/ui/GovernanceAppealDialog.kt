// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.number.GovernanceEffect
import com.wangzan101.mika.family.str
import com.wangzan101.mika.number.GovernanceAppealDraft
import com.wangzan101.mika.number.NumberClient
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GovernanceAppealDialog(effect: GovernanceEffect, close: () -> Unit, submitted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = effect.case.str("id")
    var explanation by rememberSaveable(id) { mutableStateOf("") }
    var reason by rememberSaveable(id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.governance_appeal)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.governance_appeal_help))
                OutlinedTextField(value = explanation, onValueChange = { explanation = it },
                    enabled = !busy, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.governance_appeal_explanation)) })
                TextButton(enabled = !busy && explanation.isNotBlank(), onClick = {
                    work {
                        val model = checkNotNull(ProviderStore.get(context).freezeModels(ModelSession.fresh()).main)
                        reason = GovernanceAppealDraft.draft(effect.case.toString(), explanation, model)
                    }
                }) { Text(stringResource(R.string.governance_appeal_draft)) }
                OutlinedTextField(value = reason, onValueChange = { reason = it }, enabled = !busy,
                    maxLines = 5, modifier = Modifier.fillMaxWidth(),
                    isError = reason.codePointCount(0, reason.length) > 500,
                    label = { Text(stringResource(R.string.governance_appeal_reason)) })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.number_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && reason.isNotBlank() && reason.codePointCount(0, reason.length) <= 500,
                onClick = {
                    // This is the sole owner-confirmed submission entry point.
                    work {
                        val port = NumberClient.get(context).governance
                        val current = port.mine().first { it.case.str("id") == id }
                        // A lost response can be retried without opening another appeal.
                        if (current.appeal == null) {
                            check(current.canAppeal)
                            port.appeal(id, reason)
                        }
                        submitted()
                    }
                }) { Text(stringResource(R.string.governance_appeal_confirm)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(stringResource(R.string.number_cancel)) } },
    )
}
