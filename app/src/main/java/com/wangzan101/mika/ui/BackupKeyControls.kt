// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.KeyFailure
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.delay

internal fun keyStatusText(status: KeyFailure): Int = when (status) {
    KeyFailure.invalid -> R.string.key_invalid
    KeyFailure.exhausted -> R.string.key_exhausted
    KeyFailure.rate_limited -> R.string.key_rate_limited
    KeyFailure.other -> R.string.key_available
}

@Composable internal fun ProviderKeyControls(store: ProviderStore, providerId: String) {
    var entries by remember(providerId) { mutableStateOf(store.keySummaries(providerId)) }
    var adding by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    LaunchedEffect(providerId) { while (true) { entries = store.keySummaries(providerId); delay(1000) } }
    entries.forEachIndexed { index, entry ->
        Row {
            Text("••••${entry.suffix} · ${stringResource(keyStatusText(entry.status))}", Modifier.weight(1f))
            TextButton(enabled = index > 0, onClick = { store.moveProviderKey(providerId, index, index - 1); entries = store.keySummaries(providerId) }) { Text(stringResource(R.string.key_move_up)) }
            TextButton(onClick = { store.removeProviderKey(providerId, index); entries = store.keySummaries(providerId) }) { Text(stringResource(R.string.key_remove)) }
        }
    }
    TextButton(onClick = { adding = true }) { Text(stringResource(R.string.key_add_backup)) }
    if (adding) AlertDialog(onDismissRequest = { adding = false; input = "" }, title = { Text(stringResource(R.string.key_add_backup)) },
        text = { OutlinedTextField(input, { input = it }, visualTransformation = PasswordVisualTransformation(), singleLine = true) },
        confirmButton = { TextButton(enabled = input.isNotBlank(), onClick = { store.addProviderKey(providerId, input); input = ""; adding = false; entries = store.keySummaries(providerId) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { adding = false; input = "" }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable internal fun BackupModelControl(store: ProviderStore, ui: ModelSetupUi) {
    val selected by store.backup.collectAsState()
    var open by remember { mutableStateOf(false) }
    val current = store.list().firstOrNull { it.id == selected }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.model_backup) + " · " + (current?.model ?: stringResource(R.string.model_backup_none))) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.model_backup)) },
        text = { androidx.compose.foundation.lazy.LazyColumn {
            item { TextButton(onClick = { store.useBackup(null); open = false }) { Text(stringResource(R.string.model_backup_none)) } }
            ui.providers.filter { it.kind != ProviderConfig.Kind.Community }.forEach { provider ->
                ui.options(provider).forEach { model ->
                    item(key = provider.id + "/" + model.id) { TextButton(onClick = {
                        store.useBackup(provider.id, model.id, model.capabilities); open = false
                    }) { Text(provider.name + " · " + model.id) } }
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } })
}
