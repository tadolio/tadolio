// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.wangzan101.mika.ui.components.MikaButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.about.HivoConfig
import com.wangzan101.mika.community.CommunityClient
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun CommunityControls(
    store: ProviderStore,
    enabled: Boolean = HivoConfig.communityEnabled,
    clientFactory: (() -> CommunityClient)? = null,
    onboarding: Boolean = false,
    onChosen: () -> Unit = {},
) {
    if (!enabled) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Rendering the entry must not depend on cold-start client/filesystem initialization.
    // Only provider operations need an identity; random compute selection does not.
    val client by remember(context, clientFactory) { lazy { clientFactory?.invoke() ?: com.wangzan101.mika.community.CommunityClients.get(context) } }
    var currentProvider by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun action(block: () -> Unit) {
        busy = true; failed = false
        scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (_: Exception) { failed = true } finally { busy = false } }
    }
    fun choose(kind: String) {
        store.chooseCommunity(kind)
        onChosen()
    }
    if (directory) OperatorDirectory(client, invite, current = currentProvider, used = { choose("operator") }, close = { directory = false })
    if (onboarding) {
        Column {
            Text(stringResource(R.string.operator_explanation))
            OutlinedTextField(
                value = invite,
                onValueChange = { invite = it.uppercase(java.util.Locale.ROOT) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!busy) { currentProvider = false; directory = true } }),
                label = { Text(stringResource(R.string.operator_invite)) },
                enabled = !busy,
            )
            TextButton(onClick = { currentProvider = false; directory = true }, enabled = !busy) {
                Text(stringResource(operatorLookupText(invite, false)))
            }
        }
        return
    }
    Column {
        Text(stringResource(R.string.community_title))
        Text(stringResource(R.string.community_privacy))
        Text(stringResource(R.string.community_cloud_privacy))
        TextButton(onClick = { choose("compute") }, enabled = !busy) { Text(stringResource(R.string.community_use_compute)) }
        Text(stringResource(R.string.operator_explanation))
        OutlinedTextField(
            value = invite,
            onValueChange = { invite = it.uppercase(java.util.Locale.ROOT) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (!busy) { currentProvider = false; directory = true } }),
            label = { Text(stringResource(R.string.operator_invite)) },
            enabled = !busy,
        )
        TextButton(onClick = { currentProvider = false; directory = true }, enabled = !busy) { Text(stringResource(operatorLookupText(invite, false))) }
        TextButton(onClick = {
            context.startActivity(android.content.Intent(context, com.wangzan101.mika.community.MinerActivity::class.java))
        }) { Text(stringResource(R.string.community_miner_link)) }
        TextButton(onClick = { currentProvider = true; directory = true }) { Text(stringResource(R.string.community_show_provider)) }
        TextButton(onClick = { action { client.unbind() } }, enabled = !busy) { Text(stringResource(R.string.community_unbind)) }
        if (failed) Text(stringResource(R.string.community_unavailable))
    }
}
