// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.R
import com.wangzan101.mika.about.HivoConfig
import com.wangzan101.mika.family.FamilyCrypto
import com.wangzan101.mika.family.str
import com.wangzan101.mika.number.NumberClient
import com.wangzan101.mika.number.NumberEnvelope
import com.wangzan101.mika.number.NumberTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NumberScreen(back: () -> Unit, caseId: String? = null, onCaseOpened: () -> Unit = {}) {
    val elder by com.wangzan101.mika.family.ElderStore.get(LocalContext.current).enabled.collectAsState()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density,
        density.fontScale * if (elder) 1.3f else 1f)) {
        NumberInboxScreen(back, caseId, onCaseOpened)
    }
}

@Composable
private fun NumberInboxScreen(back: () -> Unit, caseId: String?, onCaseOpened: () -> Unit) {
    var inviting by rememberSaveable { mutableStateOf(false) }
    if (inviting) { ReferralScreen(back = { inviting = false }); return }
    var moving by remember { mutableStateOf<String?>(null) }
    val cameraContext = LocalContext.current
    if (moving == "scan") {
        CameraFlowScreen(back = { moving = null }, photo = { uri ->
            val chat = (cameraContext.applicationContext as com.wangzan101.mika.MikaApp).chat
            if (chat.ui.value.composer != ComposerMode.Idle) chat.setFollowUpMode(FollowUpMode.Queue)
            chat.attach(listOf(uri))
            moving = null
            back()
        }, message = { text ->
            val chat = (cameraContext.applicationContext as com.wangzan101.mika.MikaApp).chat
            if (!chat.sendCameraCode(text, com.wangzan101.mika.settings.Prerequisites.Check(emptyList()))) chat.input(text)
            moving = null
            back()
        })
        return
    }
    moving?.let { page -> NumberMoveScreen(initialPage = page) { moving = null }; return }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val client = remember { NumberClient.get(context) }
    val prefs = remember {
        context.getSharedPreferences("hivo_number_${FamilyCrypto.hash(HivoConfig.HUB_URL)}", Context.MODE_PRIVATE)
    }
    val local = remember {
        context.getSharedPreferences("hivo_number_chat_${FamilyCrypto.hash(HivoConfig.HUB_URL)}", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    var mine by remember { mutableStateOf(client.mine()) }
    var messages by remember { mutableStateOf(client.store.messages()) }
    var seen by remember { mutableStateOf(local.getStringSet("seen", emptySet()).orEmpty().toSet()) }
    var peerCommunity by remember { mutableStateOf(false) }
    var peerOfficial by remember { mutableStateOf<String?>(null) }
    val providerStore = remember { com.wangzan101.mika.settings.ProviderStore.get(context) }
    val community = providerStore.configFor(com.wangzan101.mika.core.model.ModelRole.Main)?.kind ==
        com.wangzan101.mika.settings.ProviderConfig.Kind.Community
    var selected by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(selected) {
        peerCommunity = false
        peerOfficial = null
        if (selected.isNotEmpty()) {
            runCatching { client.lookup(selected) }
                .onSuccess {
                    peerCommunity = it["community"]?.jsonPrimitive?.booleanOrNull == true
                    peerOfficial = it["official"]?.jsonPrimitive?.contentOrNull
                }
        }
    }
    var activeThread by rememberSaveable { mutableStateOf("") }
    var activeSubject by rememberSaveable { mutableStateOf("") }
    var replyTo by rememberSaveable { mutableStateOf<String?>(null) }
    var suggestion by remember { mutableStateOf<NumberTransaction?>(null) }
    var suggestionText by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("pending") }
    var transactions by remember { mutableStateOf(client.store.transactions()) }
    var target by rememberSaveable { mutableStateOf("") }
    var newSubject by rememberSaveable { mutableStateOf("") }
    var newText by rememberSaveable { mutableStateOf("") }
    var newKind by rememberSaveable { mutableStateOf("ask") }
    var message by rememberSaveable { mutableStateOf("") }
    var pending by remember {
        mutableStateOf(local.getString("pending", null)?.let { Json.parseToJsonElement(it).jsonObject }
            ?: prefs.getString("outboxBody", null)?.let { Json.parseToJsonElement(it).jsonObject })
    }
    var sending by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var autoReceipt by remember { mutableStateOf(client.autoReceiptEnabled()) }
    var autoReply by remember { mutableStateOf(client.autoReplyEnabled()) }
    var juryRecords by remember { mutableStateOf(client.juryRecords()) }
    var showingJury by rememberSaveable { mutableStateOf(false) }
    var effects by remember { mutableStateOf(client.governanceEffects()) }
    var showingEffects by rememberSaveable { mutableStateOf(false) }
    var appealCase by remember { mutableStateOf<com.wangzan101.mika.core.number.GovernanceEffect?>(null) }
    var selectedCase by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(caseId) {
        if (caseId != null) {
            selectedCase = caseId
            showingEffects = true
            onCaseOpened()
        }
    }
    var governanceJury by remember { mutableStateOf(client.settings.governanceJuryEnabled()) }
    var postStandby by remember { mutableStateOf(client.settings.postStandbyEnabled()) }
    var editingCard by rememberSaveable { mutableStateOf(false) }
    var publicCard by rememberSaveable { mutableStateOf(client.publicCard()) }
    var providerCards by remember { mutableStateOf<JsonArray>(JsonArray(emptyList())) }
    var providerUsers by remember { mutableStateOf<JsonObject?>(null) }
    var showingUsers by remember { mutableStateOf(false) }
    LaunchedEffect(mine) {
        if (mine.isNotEmpty()) while (true) {
            try {
                providerCards = client.providerCards()
                if (showingUsers) providerUsers = client.providerUsers()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { providerCards = JsonArray(emptyList()); providerUsers = null }
            delay(30000)
        }
    }
    var ownMenu by remember { mutableStateOf(false) }
    var blocked by remember(selected) { mutableStateOf(client.blocked(selected)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun savePending(value: JsonObject?) {
        check(local.edit().putString("pending", value?.toString()).commit())
        pending = value
    }
    fun refresh() {
        messages = client.store.messages()
        transactions = client.store.transactions()
        mine = client.mine()
        juryRecords = client.juryRecords()
        effects = client.governanceEffects()
        governanceJury = client.settings.governanceJuryEnabled()
        now = System.currentTimeMillis()
        val read = if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            NumberChatState.read(messages, selected, seen) else seen
        if (read != seen) {
            check(local.edit().putStringSet("seen", read).commit())
            seen = read
        }
        val draft = pending
        if (draft != null && NumberChatState.delivered(draft, messages)) savePending(null)
        blocked = client.blocked(selected)
        autoReply = client.autoReplyEnabled()
        postStandby = client.settings.postStandbyEnabled()
    }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false; refresh() }
        }
    }
    fun send(draft: JsonObject, retry: Boolean = false) {
        if (busy) return
        refresh()
        if (retry && pending == null) return
        val thread = draft["thread"]?.jsonPrimitive?.contentOrNull
        if (thread != null) client.store.setTakenOver(draft.str("to"), thread, true)
        val outbox = prefs.getString("outboxBody", null)?.let { Json.parseToJsonElement(it).jsonObject }
        if (retry && outbox == null) {
            // A flush stores the message before removing its frame.
            refresh()
            if (pending == null) return
        }
        val retryOutbox = retry && outbox != null &&
            outbox.str("to") == draft.str("to") && outbox.str("text") == draft.str("text") &&
            (draft["thread"] == null || NumberEnvelope.decode(outbox) == NumberEnvelope.decode(draft))
        val attempt = if (retry && outbox == null) JsonObject(draft - "id" + mapOf(
            "time" to JsonPrimitive(System.currentTimeMillis()),
            "before" to JsonArray(messages.map { JsonPrimitive(it.str("id")) })
        )) else draft
        savePending(if (retryOutbox) outbox else attempt)
        sending = true
        work {
            try {
                if (retryOutbox) {
                    // poll() retries the durable frame under the existing client mutex.
                    client.poll()
                    refresh()
                    check(pending == null)
                } else {
                    if (draft["thread"] != null) client.send(draft.str("to"), NumberEnvelope.decode(draft))
                    else client.send(draft.str("to"), draft.str("text"))
                    savePending(null)
                }
            } finally {
                // Retain the protocol ID so an automatic flush and a tap cannot resend the text.
                val body = prefs.getString("outboxBody", null)?.let { Json.parseToJsonElement(it).jsonObject }
                if (body != null && body.str("to") == draft.str("to") && body.str("text") == draft.str("text") &&
                    (draft["thread"] == null || NumberEnvelope.decode(body) == NumberEnvelope.decode(draft))) savePending(body)
                sending = false
            }
        }
    }
    fun open(number: String) {
        message = ""
        activeThread = ""
        activeSubject = ""
        replyTo = null
        selected = number
        transactions.firstOrNull { it.number == number }?.let {
            activeThread = it.thread
            activeSubject = it.subject
            replyTo = it.lastId
        }
        menu = false
        refresh()
    }
    BackHandler(selected.isNotEmpty()) { selected = ""; menu = false }
    LaunchedEffect(selected) {
        while (true) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refresh()
            delay(1000)
        }
    }
    val items = messages.filter { NumberChatState.peer(it) == selected }.sortedBy(NumberChatState::time)
    val draft = pending?.takeIf { it.str("to") == selected }
    val listState = rememberLazyListState()
    LaunchedEffect(selected, items.size, draft, sending) {
        val count = items.size + if (draft != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    if (showingUsers) AlertDialog(
        onDismissRequest = { showingUsers = false },
        title = { Text(stringResource(R.string.number_provider_users)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                providerCards.filter { it.jsonObject.operatorFlag("confirmed") && it.jsonObject.operatorText("kind") == "operator" }.forEach { ProviderSummary(it.jsonObject) }
                providerUsers?.let { result ->
                    Text(stringResource(R.string.number_provider_counts,
                        result["total"]?.jsonPrimitive?.intOrNull ?: 0,
                        result["withoutNumber"]?.jsonPrimitive?.intOrNull ?: 0))
                    result["users"]?.jsonArray?.forEach { item ->
                        val user = item.jsonObject
                        TextButton(onClick = { showingUsers = false; open(user.str("number")) }) {
                            Column {
                                Text(NumberChatState.formatted(user.str("number"), user["official"]?.jsonPrimitive?.contentOrNull))
                                val boundAt = user["boundAt"]?.jsonPrimitive?.longOrNull ?: 0
                                val lastUsed = user["lastUsed"]?.jsonPrimitive?.longOrNull
                                Text(stringResource(R.string.number_provider_usage,
                                    if (boundAt > 0) DateFormat.getDateTimeInstance().format(Date(boundAt)) else "—",
                                    lastUsed?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "—",
                                    user["requests30d"]?.jsonPrimitive?.intOrNull ?: 0))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showingUsers = false }) { Text(stringResource(R.string.number_back)) } },
    )
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (selected.isEmpty()) back() else { selected = ""; menu = false } }) {
                    Text(stringResource(R.string.number_back))
                }
                Text(if (selected.isEmpty()) stringResource(R.string.number_title) else NumberChatState.formatted(selected, peerOfficial),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Box {
                    TextButton(enabled = mine.isNotEmpty(), onClick = { if (selected.isEmpty()) adding = true else menu = true }) {
                        Text(if (selected.isEmpty()) "+" else stringResource(R.string.number_menu))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(if (blocked) R.string.number_unblock else R.string.number_block)) },
                            enabled = !busy, onClick = { menu = false; work { client.block(selected, !blocked) } })
                    }
                }
            }
            if (selected.isNotEmpty() && peerCommunity) Text(
                stringResource(R.string.number_community_peer), Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall)
            if (selected.isEmpty()) {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    if (mine.isEmpty()) {
                        Text(stringResource(R.string.number_preparing))
                    } else {
                        TextButton(onClick = { ownMenu = true }) {
                            Text(stringResource(R.string.number_mine, NumberChatState.formatted(mine)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    DropdownMenu(expanded = ownMenu, onDismissRequest = { ownMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.post_mine)) }, onClick = {
                            ownMenu = false
                            context.startActivity(android.content.Intent(context, com.wangzan101.mika.number.PostCardsActivity::class.java)
                                .putExtra("ownPosts", true))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.post_suggestions)) }, onClick = {
                            ownMenu = false
                            context.startActivity(android.content.Intent(context, com.wangzan101.mika.number.PostCardsActivity::class.java))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.number_public_card)) },
                            onClick = { publicCard = client.publicCard(); editingCard = true; ownMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.number_auto_reply)) },
                            trailingIcon = { Switch(checked = autoReply, onCheckedChange = null) },
                            onClick = { autoReply = !autoReply; client.setAutoReplyEnabled(autoReply) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.post_standby)) },
                            trailingIcon = { Switch(checked = postStandby, onCheckedChange = null) },
                            onClick = { postStandby = !postStandby; client.settings.setPostStandbyEnabled(postStandby) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.governance_jury)) },
                            trailingIcon = { Switch(checked = governanceJury, onCheckedChange = null) },
                            onClick = { governanceJury = !governanceJury; client.settings.setGovernanceJuryEnabled(governanceJury) })
                        if (community) Text(stringResource(R.string.number_community_owner),
                            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                        DropdownMenuItem(text = { Text(stringResource(R.string.number_auto_receipt)) },
                            trailingIcon = { Switch(checked = autoReceipt, onCheckedChange = null) },
                            onClick = { autoReceipt = !autoReceipt; client.setAutoReceiptEnabled(autoReceipt) })
                        val label = stringResource(R.string.number_title)
                        DropdownMenuItem(text = { Text(stringResource(R.string.number_copy)) }, onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, mine))
                            ownMenu = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.number_change)) }, enabled = !busy,
                            onClick = { ownMenu = false; changing = true })
                    }
                }
                NumberMoveEntry { moving = it }
                TextButton(onClick = { inviting = true }) { Text(stringResource(R.string.referral_title)) }
                providerCards.filter { it.jsonObject["confirmed"]?.jsonPrimitive?.booleanOrNull != true }.forEach { card ->
                    val proposal = card.jsonObject
                    Text(stringResource(R.string.number_provider_request, proposal.str("platform"), proposal.str("nodeId")),
                        Modifier.padding(horizontal = 16.dp))
                    TextButton(enabled = !busy, onClick = { work {
                        client.confirmProvider(proposal.str("nodeId"), true)
                        providerCards = client.providerCards()
                    } }) { Text(stringResource(R.string.number_provider_confirm)) }
                }
                if (providerCards.any { it.jsonObject.operatorFlag("confirmed") && it.jsonObject.operatorText("kind") == "operator" }) {
                    TextButton(enabled = !busy, onClick = { work {
                        providerUsers = client.providerUsers()
                        showingUsers = true
                    } }) { Text(stringResource(R.string.number_provider_users)) }
                }
                val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val todayVotes = juryRecords.count {
                    java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today
                }
                Text(stringResource(R.string.governance_today, todayVotes),
                    Modifier.fillMaxWidth().clickable { showingJury = true }.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
                val restrictions = effects.filter { it.active && it.case.str("kind") == "number" &&
                    (it.until?.let { until -> until > now } ?: true) }
                if (restrictions.isNotEmpty()) {
                    val permanent = restrictions.any { it.until == null }
                    val days = ((restrictions.mapNotNull { it.until }.maxOrNull() ?: now) - now + 86399999L) / 86400000L
                    Text(if (permanent) stringResource(R.string.governance_restricted_permanent)
                        else stringResource(R.string.governance_restricted_days, days),
                        Modifier.fillMaxWidth().clickable { selectedCase = null; showingEffects = true }.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (effects.isNotEmpty()) TextButton(onClick = { selectedCase = null; showingEffects = true }) {
                    Text(stringResource(R.string.governance_decisions))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("pending" to R.string.number_pending_section,
                        "processed" to R.string.number_processed_section,
                        "filtered" to R.string.number_filtered_section).forEach { (state, label) ->
                        TextButton(onClick = { section = state }) {
                            Text(stringResource(label), color = if (section == state)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val cards = transactions.filter { it.state == section }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (cards.isEmpty()) item {
                        Text(stringResource(R.string.number_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                    itemsIndexed(cards) { _, item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(NumberChatState.formatted(item.number, item.official), style = MaterialTheme.typography.titleMedium)
                                Text(item.subject, style = MaterialTheme.typography.titleLarge)
                                Text(item.summary, style = MaterialTheme.typography.bodyLarge)
                                if (item.autoReplies > 0) Text(stringResource(R.string.number_auto_rounds, item.autoReplies))
                                item.autoNote?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                                if (!item.suggestion.isNullOrBlank()) {
                                    Text(item.suggestion, style = MaterialTheme.typography.bodyLarge)
                                    TextButton(enabled = !busy, onClick = {
                                        suggestion = item
                                        suggestionText = item.suggestion
                                    }) { Text(stringResource(R.string.number_use_suggestion)) }
                                }
                                if (item.tier == "urgent") Text(stringResource(R.string.number_urgent),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.time)),
                                    style = MaterialTheme.typography.bodyMedium)
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    TextButton(onClick = {
                                        client.store.setTakenOver(item.number, item.thread, !item.takenOver)
                                        refresh()
                                    }) { Text(stringResource(if (item.takenOver) R.string.number_give_agent else R.string.number_take_over)) }
                                    TextButton(onClick = {
                                        open(item.number)
                                        activeThread = item.thread
                                        activeSubject = item.subject
                                        replyTo = item.lastId
                                        message = item.suggestion.orEmpty()
                                    }) { Text(stringResource(R.string.number_reply)) }
                                    TextButton(onClick = { open(item.number) }) { Text(stringResource(R.string.number_original)) }
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    TextButton(enabled = !busy, onClick = {
                                        client.store.setState(item.number, item.thread,
                                            if (section == "filtered") "pending" else "processed")
                                        refresh()
                                    }) { Text(stringResource(if (section == "filtered") R.string.number_restore else R.string.number_ignore)) }
                                    TextButton(enabled = !busy, onClick = { work { client.block(item.number, true) } }) {
                                        Text(stringResource(R.string.number_block))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (blocked) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(stringResource(R.string.number_blocked_banner), Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(items, key = { _, item -> item.str("id") }) { index, item ->
                        if (NumberChatState.separator(items.getOrNull(index - 1)?.let(NumberChatState::time), NumberChatState.time(item))) {
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(NumberChatState.time(item))),
                                Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).padding(8.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item["provider"]?.jsonPrimitive?.contentOrNull?.let { provider ->
                            Text(stringResource(R.string.number_provider_source, provider), Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        NumberBubble(item.str("text"), NumberChatState.outgoing(item))
                    }
                    if (draft != null) item(key = "pending") {
                        if (NumberChatState.separator(items.lastOrNull()?.let(NumberChatState::time), NumberChatState.time(draft))) {
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(NumberChatState.time(draft))),
                                Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).padding(8.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (sending) CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
                            else TextButton(enabled = !busy, onClick = { send(draft, retry = true) }) {
                                Text(stringResource(R.string.number_retry), color = MaterialTheme.colorScheme.error)
                            }
                            NumberBubble(draft.str("text"), true, Modifier.weight(1f))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.number_message)) }, maxLines = 5)
                    TextButton(enabled = !busy && pending == null && message.isNotBlank() && mine.isNotEmpty() &&
                        message.codePointCount(0, message.length) <= 2000, onClick = {
                        val body = buildJsonObject {
                            if (activeThread.isNotEmpty()) {
                                NumberEnvelope("reply", activeSubject, message, activeThread, replyTo).encode()
                                    .forEach { (key, value) -> put(key, value) }
                            }
                            put("to", selected); put("text", message); put("time", System.currentTimeMillis())
                            put("before", JsonArray(messages.map { JsonPrimitive(it.str("id")) }))
                        }
                        send(body)
                        message = ""
                    }) { Text(stringResource(R.string.number_send)) }
                }
            }
            if (busy && !sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.number_failed), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            if (pending != null && draft == null) TextButton(onClick = { open(pending!!.str("to")) }) {
                Text(stringResource(R.string.number_pending))
            }
        }
    }
    if (editingCard) AlertDialog(onDismissRequest = { editingCard = false },
        title = { Text(stringResource(R.string.number_public_card)) },
        text = { OutlinedTextField(value = publicCard, onValueChange = { publicCard = it },
            label = { Text(stringResource(R.string.number_public_card_hint)) },
            isError = publicCard.codePointCount(0, publicCard.length) > 1000, maxLines = 8) },
        confirmButton = { TextButton(enabled = publicCard.codePointCount(0, publicCard.length) <= 1000,
            onClick = { client.setPublicCard(publicCard); editingCard = false }) { Text(stringResource(R.string.number_save)) } },
        dismissButton = { TextButton(onClick = { editingCard = false }) { Text(stringResource(R.string.number_cancel)) } })
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.number_open)) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = target, onValueChange = { target = it; invalid = false }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = invalid,
                    label = { Text(stringResource(R.string.number_recipient)) },
                    supportingText = { if (invalid) Text(stringResource(R.string.number_invalid)) })
                OutlinedTextField(value = newSubject, onValueChange = { newSubject = it },
                    label = { Text(stringResource(R.string.number_subject)) })
                OutlinedTextField(value = newText, onValueChange = { newText = it }, maxLines = 5,
                    label = { Text(stringResource(R.string.number_message)) })
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("ask" to R.string.number_kind_ask, "inform" to R.string.number_kind_inform,
                        "propose" to R.string.number_kind_propose, "reply" to R.string.number_reply,
                        "ack" to R.string.number_kind_ack).forEach { (kind, label) ->
                        FilterChip(selected = newKind == kind, onClick = { newKind = kind },
                            label = { Text(stringResource(label)) })
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy && pending == null && newSubject.isNotBlank() &&
            newSubject.codePointCount(0, newSubject.length) <= 60 && newText.isNotBlank() &&
            newText.codePointCount(0, newText.length) <= 2000, onClick = {
            invalid = !NumberChatState.validNumber(target)
            if (!invalid) {
                val body = buildJsonObject {
                    NumberEnvelope(newKind, newSubject, newText, java.util.UUID.randomUUID().toString()).encode()
                        .forEach { (key, value) -> put(key, value) }
                    put("to", target)
                    put("time", System.currentTimeMillis())
                    put("before", JsonArray(messages.map { JsonPrimitive(it.str("id")) }))
                }
                adding = false
                send(body)
                target = ""
                newSubject = ""
                newText = ""
            }
        }) { Text(stringResource(R.string.number_send)) } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.number_cancel)) } })
    if (showingEffects) AlertDialog(onDismissRequest = { showingEffects = false },
        title = { Text(stringResource(R.string.governance_decisions)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(effects.asReversed().filter { selectedCase == null || it.case.str("id") == selectedCase }) { _, effect ->
                    SelectionContainer {
                        Column {
                            Text(stringResource(R.string.governance_enforced, effect.case.str("target")))
                            Text("${HivoConfig.HUB_URL}/v1/gov/case/${effect.case.str("id")}")
                            Text(effect.case.getValue("snapshot").toString())
                            val verdict = effect.appeal ?: effect.case
                            Text(verdict.str("status"))
                            val counts = verdict.getValue("counts").jsonObject
                            Text(stringResource(R.string.governance_uphold) + " " + counts["uphold"] + " · " +
                                stringResource(R.string.governance_dismiss) + " " + counts["dismiss"])
                            if (effect.canAppeal && now <= effect.appealUntil) TextButton(onClick = { appealCase = effect }) {
                                Text(stringResource(R.string.governance_appeal))
                            }
                            effect.appeal?.let { Text(stringResource(R.string.governance_appeal_opened, it.str("id"))) }
                            verdict.getValue("votes").jsonArray.forEach { vote ->
                                Text(stringResource(if (vote.jsonObject.str("vote") == "uphold")
                                    R.string.governance_uphold else R.string.governance_dismiss))
                                Text(vote.jsonObject.str("reason"))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showingEffects = false }) { Text(stringResource(R.string.number_back)) } })
    appealCase?.let { effect ->
        GovernanceAppealDialog(effect, close = { appealCase = null }, submitted = {
            appealCase = null
            work { client.refreshGovernanceEffects(); refresh() }
        })
    }
    if (showingJury) AlertDialog(onDismissRequest = { showingJury = false },
        title = { Text(stringResource(R.string.governance_jury)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(juryRecords.asReversed()) { _, record ->
                    Column {
                        Text(DateFormat.getDateTimeInstance().format(Date(record.at)), style = MaterialTheme.typography.bodySmall)
                        SelectionContainer {
                            Column {
                                Text(record.case.id, style = MaterialTheme.typography.labelMedium)
                                Text(record.case.snapshot)
                                record.case.reports.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Text(stringResource(if (record.ballot.vote == "uphold")
                                    R.string.governance_uphold else R.string.governance_dismiss))
                                Text(record.ballot.reason)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showingJury = false }) { Text(stringResource(R.string.number_back)) } })
    if (suggestion != null) AlertDialog(onDismissRequest = { suggestion = null },
        title = { Text(stringResource(R.string.number_use_suggestion)) },
        text = { OutlinedTextField(value = suggestionText, onValueChange = { suggestionText = it },
            label = { Text(stringResource(R.string.number_message)) }) },
        confirmButton = { TextButton(enabled = !busy && pending == null && suggestionText.isNotBlank() &&
            suggestionText.codePointCount(0, suggestionText.length) <= 2000, onClick = {
            val item = suggestion!!
            val body = buildJsonObject {
                NumberEnvelope("reply", item.subject, suggestionText, item.thread, item.lastId).encode()
                    .forEach { (key, value) -> put(key, value) }
                put("to", item.number)
                put("time", System.currentTimeMillis())
                put("before", JsonArray(messages.map { JsonPrimitive(it.str("id")) }))
            }
            suggestion = null
            send(body)
        }) { Text(stringResource(R.string.number_send)) } },
        dismissButton = { TextButton(onClick = { suggestion = null }) { Text(stringResource(R.string.number_cancel)) } })
    if (changing) AlertDialog(onDismissRequest = { changing = false }, title = { Text(stringResource(R.string.number_change)) },
        text = { Text(stringResource(R.string.number_change_confirm)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { changing = false; work { client.claim(true) } }) { Text(stringResource(R.string.number_change)) } },
        dismissButton = { TextButton(onClick = { changing = false }) { Text(stringResource(R.string.number_cancel)) } })
}

@Composable
private fun NumberBubble(text: String, outgoing: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(modifier = Modifier.widthIn(max = 300.dp), shape = RoundedCornerShape(14.dp),
            color = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
            SelectionContainer { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
