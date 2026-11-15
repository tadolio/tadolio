// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R

class StandbyActivity : ComponentActivity() {
    private var pendingEnable = false
    private val enabledState = mutableStateOf(false)
    private fun hasAccess() = getSystemService(android.app.NotificationManager::class.java)
        .isNotificationListenerAccessGranted(android.content.ComponentName(this, StandbyService::class.java))
    private fun enable() {
        val prefs = StandbyStore.get(this)
        prefs.setChecking(true)
        enabledState.value = true
        pendingEnable = false
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 21)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_enable", pendingEnable)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (pendingEnable && hasAccess()) enable()
        enabledState.value = StandbyStore.get(this).enabled() && StandbyStore.get(this).sms()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingEnable = savedInstanceState?.getBoolean("pending_enable") ?: false
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val prefs = remember { StandbyStore.get(this) }
            var enabled by enabledState
            var consent by remember { mutableStateOf(prefs.consent()) }
            var records by remember { mutableStateOf(prefs.records()) }
            val elder by ElderStore.get(this).enabled.collectAsState()
            val size = if (elder) 28.sp else 22.sp
            fun authorize() {
                if (hasAccess()) enable() else {
                    pendingEnable = true
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            MaterialTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.standby_title), fontSize = 32.sp)
                    Text(stringResource(R.string.standby_privacy), fontSize = size)
                    Row {
                        Switch(enabled, { value ->
                            if (value) authorize() else {
                                pendingEnable = false
                                prefs.setChecking(false)
                                enabled = false
                            }
                        })
                        Text(stringResource(R.string.standby_sms), fontSize = size)
                    }
                    Row { Switch(consent, { prefs.set("community", it); consent = it }); Text(stringResource(R.string.standby_community), fontSize = size) }
                    Text(stringResource(R.string.standby_records), fontSize = 30.sp)
                    TextButton({ prefs.clear(); records = emptyList(); getSystemService(android.app.NotificationManager::class.java).cancel(2100) }) {
                        Text(stringResource(R.string.standby_clear), fontSize = size)
                    }
                    records.forEach { r ->
                        HorizontalDivider()
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.at)) + " · " + r.sender, fontSize = size)
                        Text(stringResource(if (r.local) R.string.standby_local_reason else if (r.verdict == ScamVerdict.Suspicious) R.string.scam_suspicious else R.string.scam_uncertain), fontSize = size)
                        Text(r.reason, fontSize = size)
                        Text(stringResource(R.string.standby_advice), fontSize = size)
                    }
                    TextButton({ finish() }) { Text(stringResource(R.string.ok_got_it), fontSize = size) }
                }
            }
        }
    }
}
