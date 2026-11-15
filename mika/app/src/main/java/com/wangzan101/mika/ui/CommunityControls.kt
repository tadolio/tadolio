// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
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
    val configs by store.configs.collectAsState()
    val roles by store.roles.collectAsState()
    val retainedOwn by store.hybridOwnRoles.collectAsState()
    val hybrid by store.hybridSelected.collectAsState()
    val ownIds = configs.filter { it.kind != ProviderConfig.Kind.Community }.map { it.id }.toSet()
    val ownChoices = if (roles.main in ownIds && ownIds.containsAll(roles.referenced)) roles else retainedOwn
    val ownAvailable = ownChoices.main != null && ownIds.containsAll(ownChoices.referenced)
    fun choose(kind: String) {
        // Retain explicit Own roles before community selection prunes unused configs.
        if (roles.main in ownIds && ownIds.containsAll(roles.referenced)) store.retainHybridOwnRoles(roles)
        store.selectHybrid(false)
        val p = store.connect("Tadolio Community", ProviderConfig.Kind.Community, HivoConfig.HUB_URL, null,
            models = listOf(ListedModel("compute", toolCalls = true, vision = false), ListedModel("operator", toolCalls = true, vision = false)))
        store.use(ModelRole.Main, p.id, kind, Capabilities(toolCalls = true, vision = false, contextTokens = 32000, reasoning = false))
    }
    if (directory) OperatorDirectory(client, invite, current = currentProvider, used = { choose("operator") }, close = { directory = false })
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
        if (onboarding) return@Column
        Text(stringResource(R.string.hybrid_description))
        TextButton(onClick = {
            action {
                if (store.retainHybridOwnRoles(ownChoices)) {
                    val bound = client.binding()["nodeId"]?.toString()?.trim('"')
                    val route = if (bound != null && bound != "null") "operator" else "compute"
                    val active = store.configFor(ModelRole.Main)
                    if (active?.kind != ProviderConfig.Kind.Community || active.model != route) choose(route)
                    check(store.selectHybrid(true)) { "hybrid_unavailable" }
                }
            }
        }, enabled = !busy && ownAvailable) {
            Text(stringResource(if (hybrid) R.string.hybrid_selected else R.string.hybrid_title))
        }
        if (!ownAvailable) Text(stringResource(R.string.hybrid_requires_own))
        TextButton(onClick = { currentProvider = true; directory = true }) { Text(stringResource(R.string.community_show_provider)) }
        TextButton(onClick = { action { client.unbind() } }, enabled = !busy) { Text(stringResource(R.string.community_unbind)) }
        if (failed) Text(stringResource(R.string.community_unavailable))
    }
}
