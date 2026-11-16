// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.core.model.KeyFailure
import com.wangzan101.mika.ui.keyStatusText
import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.number.NumberClient
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.appearanceDark
import com.wangzan101.mika.ui.theme.systemBarsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class MinerActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && MinerStore(this).enabled) startForegroundService(Intent(this, MinerService::class.java))
        else MinerStore(this).enabled = false
    }
    private fun startMiner() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startForegroundService(Intent(this, MinerService::class.java))
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            val dark = appearanceDark()
            SideEffect { systemBarsFor(dark) }
            MikaTheme(dark = dark) { MinerSettings() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable private fun MinerSettings() {
        val store = remember { MinerStore(this) }
        val operator = MinerStore.activeKind(this) == "operator"
        var switchTo by remember { mutableStateOf<String?>(null) }
        var resetInvite by remember { mutableStateOf(false) }
        var editCard by remember { mutableStateOf(false) }
        var providerCard by remember { mutableStateOf<JsonObject?>(null) }
        var saved by remember { mutableStateOf(runCatching { store.read() }.getOrDefault(buildJsonObject {})) }
        fun value(name: String) = saved[name]?.jsonPrimitive?.content.orEmpty()
        var cardName by remember { mutableStateOf(value("name")) }
        var intro by remember { mutableStateOf(value("intro")) }
        var contact by remember { mutableStateOf(value("contact")) }
        var share by remember { mutableStateOf(value("share").ifBlank { "0" }) }
        var maxUsers by remember { mutableStateOf(value("maxUsers").ifBlank { "20" }) }
        var declared by remember { mutableStateOf(false) }
        var invite by remember { mutableStateOf(value("invite")) }
        val default = MinerPlatforms.all.first()
        var base by remember { mutableStateOf(value("base").ifBlank { default.base }) }
        var other by remember { mutableStateOf(MinerPlatforms.find(base) == null) }
        var keyRows by remember(base) { mutableStateOf(
            (if (base == value("base")) store.keys(saved) else emptyList()).map { it to "" }.ifEmpty { listOf("" to "") }) }
        var keyStatuses by remember { mutableStateOf(store.keyStatuses(saved)) }
        var activeKey by remember { mutableIntStateOf(store.activeKey) }
        var model by remember { mutableStateOf(value("model").ifBlank { MinerPlatforms.find(base)?.model.orEmpty() }) }
        var limit by remember { mutableStateOf((if(operator && value("limit").isBlank()) "20000000" else MinerPolicy.initialLimit(value("limit"))).toLongOrNull()) }
        var wifi by remember { mutableStateOf(saved["wifi"]?.jsonPrimitive?.booleanOrNull ?: MinerPolicy.DEFAULT_WIFI_ONLY) }
        var charging by remember { mutableStateOf(saved["charging"]?.jsonPrimitive?.booleanOrNull ?: MinerPolicy.DEFAULT_CHARGING_ONLY) }
        var minBattery by remember { mutableStateOf(value("minBattery").ifBlank { MinerPolicy.DEFAULT_MIN_BATTERY.toString() }) }
        var autoStart by remember { mutableStateOf(store.autoStart) }
        var inputPrice by remember { mutableStateOf(value("inputPrice")) }
        var outputPrice by remember { mutableStateOf(value("outputPrice")) }
        var currency by remember { mutableStateOf(value("currency").ifBlank { "USD" }) }
        var publicQuote by remember { mutableStateOf<MinerPricing.Quote?>(null) }
        var more by remember { mutableStateOf(false) }
        var details by remember { mutableStateOf(false) }
        var showCost by remember { mutableStateOf(true) }
        var changeLimit by remember { mutableStateOf(false) }
        var draftLimit by remember { mutableStateOf(limit) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<Int?>(null) }
        var enabled by remember { mutableStateOf(store.enabled) }
        var examStatus by remember { mutableStateOf(store.examStatus) }
        var state by remember { mutableStateOf(store.state()) }
        var used by remember { mutableLongStateOf(store.used()) }
        var pending by remember { mutableStateOf(store.pending()) }
        var confirmed by remember { mutableLongStateOf(store.confirmed()) }
        var nodeId by remember { mutableStateOf(value("nodeId")) }
        var number by remember { mutableStateOf(NumberClient.get(this).mine()) }
        // Follow the actual fallback copy, including English in Traditional Chinese locales.
        val chinese = stringResource(R.string.miner_limit_unit) == "万 token"
        val scope = rememberCoroutineScope()
        val uri = LocalUriHandler.current
        val platform = if (other) null else MinerPlatforms.find(base)
        val platformName = if (platform?.id == "siliconflow") stringResource(R.string.miner_siliconflow_name)
            else platform?.name ?: runCatching { java.net.URI(base).host }.getOrNull().orEmpty()
        LaunchedEffect(base, model) {
            publicQuote = null
            delay(400)
            publicQuote = withContext(Dispatchers.IO) { MinerPricing.lookup(base, model) }
        }
        LaunchedEffect(operator) {
            if(operator) while(true) {
                withContext(Dispatchers.IO) { runCatching {
                    val cfg=store.read()
                    if(cfg["nodeId"] != null) {
                        invite=MinerWorker(this@MinerActivity,store){false}.invite()
                        providerCard=com.wangzan101.mika.family.FamilyClient.get(this@MinerActivity).call("/v1/number/provider/cards").jsonArray
                            .map { it.jsonObject }.firstOrNull { it["nodeId"] == cfg["nodeId"] }
                    }
                } }
                delay(15000)
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                enabled = store.enabled
                state = store.state()
                examStatus = store.examStatus
                keyStatuses = store.keyStatuses()
                activeKey = store.activeKey
                used = store.used()
                pending = store.pending()
                confirmed = store.confirmed()
                number = NumberClient.get(this@MinerActivity).mine()
                nodeId = withContext(Dispatchers.IO) { runCatching { store.read()["nodeId"]?.jsonPrimitive?.content }.getOrNull().orEmpty() }
                delay(1000)
            }
        }
        val statusPage = enabled || state == MinerState.KEY_BAD
        val apiKeys = keyRows.map { (saved, draft) -> draft.trim().ifEmpty { saved } }
        val apiKey = apiKeys.firstOrNull().orEmpty()
        val presetPrice = MinerPlatforms.price(base, model)
        val manual = MinerPricing.estimate(1, inputPrice.toDoubleOrNull(), outputPrice.toDoubleOrNull()) != null && currency.matches(Regex("[A-Z]{3}"))
        val priceInput = if (manual) inputPrice.toDoubleOrNull() else publicQuote?.input ?: presetPrice?.input
        val priceOutput = if (manual) outputPrice.toDoubleOrNull() else publicQuote?.output ?: presetPrice?.output
        val priceCurrency = if (manual) currency else if (publicQuote != null) "USD" else presetPrice?.currency.orEmpty()
        val prices = MinerPricing.estimate(limit, priceInput, priceOutput)
        val valid = apiKeys.size in 1..5 && apiKeys.all { it.isNotBlank() } && apiKeys.distinct().size == apiKeys.size && (!operator || OperatorForm.valid(cardName,intro,contact,share.toIntOrNull(),maxUsers.toIntOrNull(),declared)) && MinerPolicy.canEnable(base, model, apiKey, limit, minBattery.toIntOrNull(), number)
        val editable = !statusPage && !busy

        @Composable fun ProviderCardFields() {
            MinerField(cardName, { cardName = it }, R.string.phone_operator_name, !busy)
            MinerField(intro, { intro = it }, R.string.phone_operator_intro, !busy)
            MinerField(contact, { contact = it }, R.string.phone_operator_contact, !busy)
            Text(stringResource(R.string.phone_operator_online))
            MinerField(share, { share = it }, R.string.phone_operator_share, !busy, KeyboardType.Number)
            Text(stringResource(R.string.phone_operator_share_hint), style = MaterialTheme.typography.bodySmall)
            MinerField(maxUsers, { maxUsers = it }, R.string.phone_operator_capacity, !busy, KeyboardType.Number)
            Text(stringResource(minerNumberMessage(number), number))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(declared, { declared = it })
                Text(stringResource(R.string.phone_operator_declaration))
            }
        }

        @Composable fun RoleChoice() {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (role in listOf("compute", "operator")) FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = operator == (role == "operator"), enabled = !busy,
                    onClick = { if (operator != (role == "operator")) switchTo = role },
                    label = { Text(stringResource(if (role == "operator") R.string.phone_operator_role else R.string.phone_miner_role)) })
            }
            Text(stringResource(if (operator) R.string.phone_operator_role_sub else R.string.phone_miner_role_sub), style = MaterialTheme.typography.bodySmall)
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { TopAppBar(title = { Text(stringResource(if (operator) R.string.phone_operator_title else R.string.miner_title)) }, navigationIcon = {
                IconButton(onClick = { finish() }) { Icon(MikaIcons.Back, stringResource(R.string.miner_back)) }
            }) },
            bottomBar = {
                Surface {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (statusPage) {
                            OutlinedButton(onClick = {
                                store.enabled = false
                                stopService(Intent(this@MinerActivity, MinerService::class.java))
                                store.phase = MinerState.CONNECTING
                                store.pauseReason = null
                                state = MinerState.CONNECTING
                                enabled = false
                            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.miner_stop)) }
                        } else {
                            Button(enabled = !busy && valid, modifier = Modifier.fillMaxWidth(), onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) { MinerKeyValidator().validate(base, model, apiKeys) }
                                        if (result != MinerKeyValidator.Result.Valid) { error = result.message; return@launch }
                                        saved = withContext(Dispatchers.IO) {
                                            store.update { current ->
                                                val same = base.trimEnd('/') == current["base"]?.jsonPrimitive?.content && model == current["model"]?.jsonPrimitive?.content
                                                buildJsonObject {
                                                    if (same) current.forEach { (k, v) -> put(k, v) }
                                                    put("kind", if(operator) "operator" else "compute")
                                                    if(operator) {
                                                        put("name",cardName.trim());put("intro",intro);put("contact",contact)
                                                        put("share",share.toInt());put("maxUsers",maxUsers.toInt())
                                                    }
                                                    put("base", base.trimEnd('/'))
                                                    put("key", apiKey)
                                                    putJsonArray("keys") { apiKeys.forEach { add(it) } }
                                                    put("model", model)
                                                    put("limit", limit!!)
                                                    put("minBattery", minBattery.toInt())
                                                    put("inputPrice", inputPrice)
                                                    put("outputPrice", outputPrice)
                                                    put("currency", currency)
                                                    put("metered", true)
                                                    put("wifi", wifi)
                                                    put("charging", charging)
                                                    put("number", number)
                                                }
                                            }.also { store.autoStart = autoStart }
                                        }
                                        store.resetKeys(keyRows.filter { it.second.isNotBlank() }.map { it.second.trim() })
                                        keyRows = apiKeys.map { it to "" }
                                        store.phase = MinerState.CONNECTING
                                        store.pauseReason = null
                                        state = MinerState.CONNECTING
                                        store.enabled = true
                                        startMiner()
                                        enabled = store.enabled
                                    } catch (_: Exception) { store.enabled = false; enabled = false; error = R.string.miner_save_failed }
                                    finally { busy = false }
                                }
                            }) { Text(stringResource(if (busy) R.string.miner_checking else R.string.miner_start)) }
                            Text(stringResource(R.string.miner_exam), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!statusPage) {
                    Text(stringResource(R.string.miner_what_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.miner_intro), style = MaterialTheme.typography.bodyMedium)
                }
                if (!statusPage) RoleChoice()
                if (statusPage) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val color = when (state) {
                                MinerState.ONLINE, MinerState.WORKING -> MaterialTheme.colorScheme.primary
                                MinerState.KEY_BAD, MinerState.FAILED_EXAM -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.tertiary
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("●", color = color)
                                Text(stringResource(state.message), style = MaterialTheme.typography.titleLarge)
                            }
                            if (examStatus["retryAllowed"]?.jsonPrimitive?.booleanOrNull == true) {
                                Text(stringResource(if (examStatus["failureReason"]?.jsonPrimitive?.contentOrNull == "timeout") R.string.miner_exam_timeout else R.string.miner_exam_answer))
                                val remaining=examStatus["retryRemaining"]?.jsonPrimitive?.intOrNull ?: 0
                                Button(enabled=!busy && remaining > 0, onClick={ scope.launch {
                                    busy=true
                                    try {
                                        withContext(Dispatchers.IO) { MinerWorker(this@MinerActivity,store){false}.retryExam() }
                                        examStatus=store.examStatus
                                        state=store.state()
                                    } catch(_:Exception) { error=R.string.miner_save_failed }
                                    finally { busy=false }
                                } }) { Text(stringResource(R.string.miner_exam_retry,remaining)) }
                                TextButton(enabled=!busy,onClick={
                                    store.enabled=false
                                    stopService(Intent(this@MinerActivity,MinerService::class.java))
                                    enabled=false
                                    store.phase=MinerState.CONNECTING
                                    state=MinerState.CONNECTING
                                    more=true
                                }) { Text(stringResource(R.string.miner_change_model)) }
                                if (remaining == 0) {
                                    val reset=examStatus["retryResetAt"]?.jsonPrimitive?.longOrNull ?: 0
                                    Text(stringResource(R.string.miner_exam_reset,java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).apply { timeZone=java.util.TimeZone.getTimeZone("Asia/Shanghai") }.format(java.util.Date(reset)) + " (UTC+8)"))
                                }
                            }
                            Text(stringResource(R.string.key_active, activeKey))
                            if (state == MinerState.KEY_BAD) Text(stringResource(when {
                                keyStatuses.all { it == KeyFailure.invalid } -> R.string.keys_all_invalid
                                keyStatuses.all { it == KeyFailure.exhausted } -> R.string.keys_all_exhausted
                                keyStatuses.all { it == KeyFailure.rate_limited } -> R.string.keys_all_limited
                                else -> R.string.keys_all_unavailable
                            }, keyStatuses.size))
                            Text(stringResource(R.string.miner_cloud, platformName) + " · " + model, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.miner_today) + " " + stringResource(R.string.miner_today_usage,
                                    MinerLimits.display(used, chinese), MinerLimits.display(limit ?: 0, chinese)), modifier = Modifier.weight(1f))
                                TextButton(onClick = { draftLimit = limit; changeLimit = true }) { Text(stringResource(R.string.miner_change)) }
                            }
                            LinearProgressIndicator(progress = { (used.toFloat() / (limit ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.miner_coins) + " · " + stringResource(R.string.miner_coins_value, pending, confirmed.toString()))
                            MinerToggle(stringResource(R.string.miner_auto_start), autoStart, { autoStart = it; store.autoStart = it })
                        }
                    }
                }
                if(operator && statusPage) {
                    providerCard?.let { card ->
                        Text(stringResource(R.string.operator_counts,card["users"]?.jsonPrimitive?.intOrNull ?: 0,card["boundUsers"]?.jsonPrimitive?.intOrNull ?: 0,card["maxUsers"]?.jsonPrimitive?.intOrNull ?: 20))
                        Text(stringResource(if(card["listed"]?.jsonPrimitive?.booleanOrNull == true) R.string.operator_listed else R.string.operator_unlisted))
                    }
                    Text(stringResource(R.string.phone_operator_invite_label), style = MaterialTheme.typography.bodySmall)
                    Text(invite,style=MaterialTheme.typography.headlineLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled=!busy,onClick={ scope.launch {
                            busy=true
                            try { invite=withContext(Dispatchers.IO){MinerWorker(this@MinerActivity,store){false}.invite()} }
                            catch(_:Exception){error=R.string.miner_save_failed} finally {busy=false}
                        } }) { Text(stringResource(R.string.operator_refresh)) }
                        TextButton(onClick={getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Tadolio",invite))}) {Text(stringResource(R.string.number_copy))}
                        val shareText=stringResource(R.string.operator_share_link,invite,com.wangzan101.mika.number.ReferralLink.operatorWeb(invite))
                        TextButton(onClick={startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,shareText),null))}) {Text(stringResource(R.string.operator_share_action))}
                        TextButton(onClick={resetInvite=true}) {Text(stringResource(R.string.phone_operator_reset))}
                    }
                    TextButton(onClick={editCard=!editCard}){Text(stringResource(R.string.phone_operator_edit))}
                    if (editCard) ProviderCardFields()
                    if(editCard) TextButton(enabled=!busy && OperatorForm.valid(cardName,intro,contact,share.toIntOrNull(),maxUsers.toIntOrNull(),declared),onClick={scope.launch {
                        busy=true
                        try {withContext(Dispatchers.IO){
                            store.update {JsonObject(it + mapOf("name" to JsonPrimitive(cardName.trim()),"intro" to JsonPrimitive(intro),"contact" to JsonPrimitive(contact),"share" to JsonPrimitive(share.toInt()),"maxUsers" to JsonPrimitive(maxUsers.toInt())))}
                            MinerWorker(this@MinerActivity,store){false}.submitCard()
                        };editCard=false}catch(_:Exception){error=R.string.miner_save_failed}finally{busy=false}
                    }}){Text(stringResource(android.R.string.ok))}
                }
                if (!statusPage) {
                    MinerStep(1, R.string.miner_step_platform)
                    FlowRow(maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MinerPlatforms.all.forEach { item ->
                            FilterChip(selected = platform == item, onClick = {
                                other = false; base = item.base; model = item.model; keyRows = listOf("" to ""); error = null
                                inputPrice = ""; outputPrice = ""
                            }, enabled = editable, modifier = Modifier.weight(1f), label = {
                                Text(if (item.id == "siliconflow") stringResource(R.string.miner_siliconflow_name) else item.name, style = MaterialTheme.typography.labelMedium)
                            })
                        }
                        FilterChip(selected = other, onClick = {
                            if (!other) { other = true; base = ""; model = ""; keyRows = listOf("" to ""); inputPrice = ""; outputPrice = ""; error = null }
                        }, enabled = editable, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.miner_platform_other), style = MaterialTheme.typography.labelMedium) })
                    }
                    if (other) {
                        Text(stringResource(R.string.miner_other_hint), style = MaterialTheme.typography.bodySmall)
                        MinerField(base, { base = it.trim(); keyRows = listOf("" to ""); inputPrice = ""; outputPrice = ""; error = null }, R.string.miner_base, editable)
                    } else platform?.let { item ->
                        MinerLinkedHint(stringResource(item.description), stringResource(R.string.miner_get_key)) {
                            runCatching { uri.openUri(item.keyUrl) }
                        }
                    }
                    MinerStep(2, R.string.miner_step_key)
                    keyRows.forEachIndexed { index, (stored, draft) ->
                        OutlinedTextField(draft, { value -> keyRows = keyRows.toMutableList().also { it[index] = stored to value }; error = null },
                            placeholder = { Text(if (stored.isBlank()) stringResource(R.string.miner_key) else "\u2022\u2022\u2022\u2022" + stored.takeLast(4)) },
                            enabled = editable, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Row {
                            Text(stringResource(keyStatusText(if (draft.isNotBlank()) KeyFailure.other else keyStatuses.getOrElse(index) { KeyFailure.other })), Modifier.weight(1f))
                            TextButton(enabled = editable && index > 0, onClick = {
                                keyRows = keyRows.toMutableList().also { it.add(index - 1, it.removeAt(index)) }
                            }) { Text(stringResource(R.string.key_move_up)) }
                            TextButton(enabled = editable, onClick = { keyRows = keyRows.filterIndexed { i, _ -> i != index } }) { Text(stringResource(R.string.key_remove)) }
                        }
                    }
                    TextButton(enabled = editable && keyRows.size < 5, onClick = { keyRows = keyRows + ("" to "") }) { Text(stringResource(R.string.key_add_backup)) }
                    Text(stringResource(R.string.miner_api_only), style = MaterialTheme.typography.bodySmall)
                    MinerField(model, { model = it.trim(); inputPrice = ""; outputPrice = "" }, R.string.miner_model, editable)
                    Text(if(platform == null) stringResource(R.string.miner_model_custom) else stringResource(R.string.miner_model_default,platform.model),style=MaterialTheme.typography.bodySmall)
                    MinerStep(3, R.string.miner_step_limit)
                    MinerLimitPicker(limit, { limit = it }, chinese, editable, operator)
                    MinerLinkedHint(stringResource(R.string.miner_limit_jobs, MinerLimits.jobs(limit)) + " " + stringResource(R.string.miner_limit_note), stringResource(R.string.miner_cost_link)) { showCost = !showCost }
                    if (showCost) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Text(if (prices == null) stringResource(R.string.miner_cost_unknown) else stringResource(R.string.miner_cost_estimate, platformName, priceCurrency,
                                String.format(java.util.Locale.ROOT, "%.2f", prices.first), String.format(java.util.Locale.ROOT, "%.2f", prices.second)), Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (operator) ProviderCardFields()
                    else Text(stringResource(minerNumberMessage(number), number), style = MaterialTheme.typography.bodySmall)
                }
                if (statusPage) RoleChoice()
                TextButton(onClick = { more = !more }, contentPadding = PaddingValues(0.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.miner_more) + if (more) " ‹" else " ›")
                        if (!statusPage) Text(stringResource(R.string.miner_more_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                if (more) {
                    if (statusPage) Text(stringResource(R.string.miner_locked_while_running), style = MaterialTheme.typography.bodySmall)
                    MinerToggle(stringResource(R.string.miner_wifi), wifi, { wifi = it }, editable)
                    MinerToggle(stringResource(R.string.miner_charging), charging, { charging = it }, editable)
                    MinerField(minBattery, { minBattery = it }, R.string.miner_battery, editable, KeyboardType.Number)
                    if (!statusPage) MinerToggle(stringResource(R.string.miner_auto_start), autoStart, { autoStart = it; store.autoStart = it }, !busy)
                    Text(stringResource(R.string.miner_price_manual), style = MaterialTheme.typography.bodySmall)
                    MinerField(inputPrice, { inputPrice = it }, R.string.miner_price_input, editable, KeyboardType.Decimal)
                    MinerField(outputPrice, { outputPrice = it }, R.string.miner_price_output, editable, KeyboardType.Decimal)
                    MinerField(currency, { currency = it.uppercase(java.util.Locale.ROOT) }, R.string.miner_currency, editable)
                    Text(stringResource(R.string.miner_price_note, MinerPlatforms.UPDATED), style = MaterialTheme.typography.bodySmall)
                }
                if (statusPage) {
                    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.miner_details)) }
                    if (details) {
                        Text(stringResource(R.string.miner_node, nodeId))
                        Text(stringResource(minerNumberMessage(number), number))
                        Text(stringResource(R.string.points_disclaimer), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        switchTo?.let { target -> AlertDialog(onDismissRequest={switchTo=null},text={Text(stringResource(R.string.phone_operator_switch))},confirmButton={TextButton(onClick={
            store.enabled=false
            stopService(Intent(this@MinerActivity,MinerService::class.java))
            MinerStore.select(this@MinerActivity,target)
            recreate()
        }){Text(stringResource(android.R.string.ok))}},dismissButton={TextButton(onClick={switchTo=null}){Text(stringResource(android.R.string.cancel))}}) }
        if(resetInvite) AlertDialog(onDismissRequest={resetInvite=false},text={Text(stringResource(R.string.phone_operator_reset_confirm))},confirmButton={TextButton(onClick={resetInvite=false;scope.launch {
            try {invite=withContext(Dispatchers.IO){MinerWorker(this@MinerActivity,store){false}.invite(true)}}catch(_:Exception){error=R.string.miner_save_failed}
        }}){Text(stringResource(android.R.string.ok))}},dismissButton={TextButton(onClick={resetInvite=false}){Text(stringResource(android.R.string.cancel))}})
        if (changeLimit) AlertDialog(onDismissRequest = { changeLimit = false }, title = { Text(stringResource(R.string.miner_step_limit)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MinerLimitPicker(draftLimit, { draftLimit = it }, chinese, true, operator)
                Text(stringResource(R.string.miner_limit_jobs, MinerLimits.jobs(draftLimit)) + stringResource(R.string.miner_limit_note), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = draftLimit != null, onClick = {
                try {
                    store.changeLimit(draftLimit!!)
                    limit = draftLimit
                    if (store.enabled) startForegroundService(Intent(this@MinerActivity, MinerService::class.java))
                    changeLimit = false
                } catch (_: Exception) { error = R.string.miner_save_failed }
            }) { Text(stringResource(android.R.string.ok)) }
        }, dismissButton = { TextButton(onClick = { changeLimit = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

@Composable private fun MinerStep(number: Int, title: Int) {
    Text("$number  " + stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable private fun MinerLinkedHint(text: String, link: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Text(buildAnnotatedString {
        append(text)
        append(" ")
        withLink(LinkAnnotation.Clickable("miner_link", TextLinkStyles(SpanStyle(color = color))) { onClick() }) { append(link) }
    }, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun MinerField(value: String, change: (String) -> Unit, label: Int, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, change, label = { Text(stringResource(label)) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
}

@Composable private fun MinerToggle(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, change, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MinerLimitPicker(value: Long?, change: (Long?) -> Unit, chinese: Boolean, enabled: Boolean, operator: Boolean = false) {
    val presets = if(operator) OperatorForm.presets else MinerLimits.presets
    var custom by remember { mutableStateOf(value !in presets) }
    var input by remember(chinese) { mutableStateOf(value?.let { MinerLimits.display(it, chinese) }.orEmpty()) }
    val labels = listOf(R.string.miner_limit_50, R.string.miner_limit_200, R.string.miner_limit_500, R.string.miner_limit_custom)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(selected = if (index == 3) custom else !custom && value == presets[index],
                onClick = {
                    custom = index == 3
                    change(if (custom) MinerLimits.parse(input, chinese) else presets[index])
                }, enabled = enabled, shape = SegmentedButtonDefaults.itemShape(index, labels.size), icon = {}) {
                Text(if(operator && index < 3) MinerLimits.display(presets[index],chinese) + if(chinese) "万" else "M" else stringResource(label), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (custom) MinerField(input, { input = it; change(MinerLimits.parse(it, chinese)) }, R.string.miner_limit_unit, enabled, KeyboardType.Decimal)
}
