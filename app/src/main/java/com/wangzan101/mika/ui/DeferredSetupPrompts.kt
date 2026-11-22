// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.number.NumberClient
import com.wangzan101.mika.settings.DeferredSetup
import com.wangzan101.mika.settings.DeferredSetupPolicy
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Lives in the conversation, including the elder home. It never runs during setup. */
@Composable
internal fun DeferredSetupPrompts(ui: ChatUi) {
    val context = LocalContext.current
    val state = remember { DeferredSetup(context) }
    var battery by remember { mutableStateOf(false) }
    var referrer by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf(false) }
    val hasResult = ui.exchanges.any { it.status is ExchangeStatus.Done }
    LaunchedEffect(hasResult) {
        if (hasResult) state.recordResult()
        var checked = false
        while (true) {
            battery = DeferredSetupPolicy.battery(state.batteryNeeded(), false,
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
            if (!checked && state.hasResult() && !state.referrerDismissed()) {
                try {
                    val client = NumberClient.get(context)
                    if (client.mine().isNotEmpty()) {
                        val canBind = client.referralStatus()["canBind"]?.jsonPrimitive?.booleanOrNull == true
                        referrer = DeferredSetupPolicy.referrer(true, canBind, state.referrerDismissed())
                        checked = true
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Retry the read while this conversation is visible. */ }
            }
            delay(2000)
        }
    }
    // The chat top bar floats over the message list, so these prompts need their own opaque background.
    Column(Modifier.fillMaxWidth().background(MikaTheme.colors.bg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (battery) {
            val copy = StatusCopy.batteryBanner()
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(copy.title, Modifier.weight(1f))
                TextButton({
                    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
                    if (runCatching { context.startActivity(request) }.isFailure)
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text(copy.link) }
            }
        }
        if (referrer) Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(R.string.t60_referrer_title), Modifier.weight(1f))
            TextButton({ state.dismissReferrer(); referrer = false; showing = true }) { Text(stringResource(R.string.t60_referrer_add)) }
            TextButton({ state.dismissReferrer(); referrer = false }) { Text(stringResource(R.string.t60_referrer_dismiss)) }
        }
    }
    if (showing) androidx.compose.ui.window.Dialog(onDismissRequest = { showing = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) { ReferralScreen(back = { showing = false }) }
}
