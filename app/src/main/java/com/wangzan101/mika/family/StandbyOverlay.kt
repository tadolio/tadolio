// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.*
import com.wangzan101.mika.ui.theme.MikaTheme

/** No message bodies or sender identities; only the current fixed warning is retained. */
internal object StandbyOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Pair<String, String>? = null
    private var window: OverlayWindow? = null
    private var owner: ScreenAccessibility? = null
    private var context: Context? = null
    fun show(context: Context, conclusion: String, suffix: String) {
        main.post {
            this.context = context.applicationContext
            pending = conclusion to suffix
            close()
            main.removeCallbacks(tick)
            tick.run()
        }
    }
    fun refresh() {
        if (pending == null) return
        main.removeCallbacks(tick)
        tick.run()
    }
    private fun close() { window?.close(); window = null; owner = null }
    private fun dismiss() { pending = null; close(); main.removeCallbacks(tick); context = null }
    private val tick = object : Runnable {
        override fun run() {
            val app = context ?: return
            val warning = pending ?: return
            val service = ScreenAccessibility.instance.value
            val safe = service != null && runCatching {
                val windows = service.windows
                val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                val blocked = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    (it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && (it.isActive || it.isFocused) &&
                        StandbyOverlayPolicy.sensitive(it.root?.packageName?.toString().orEmpty())) } || apps.isEmpty() || apps.any { w ->
                    val root = w.root
                    // Inspect only package and password flags, never text or content descriptions.
                    root == null || StandbyOverlayPolicy.sensitive(root.packageName?.toString().orEmpty()) || password(root)
                }
                StandbyOverlayPolicy.allowed(app.getSystemService(PowerManager::class.java).isInteractive,
                    app.getSystemService(KeyguardManager::class.java).isKeyguardLocked, true, blocked)
            }.getOrDefault(false)
            if (!safe || owner !== service) close()
            if (safe && window == null && service != null) {
                val elder = ElderStore.get(app).enabled.value
                window = service.overlays().open(OverlayPlacement(Gravity.CENTER), onOutside = {}) {
                    MikaTheme { Surface(tonalElevation = 8.dp) {
                        Column(Modifier.widthIn(max = 340.dp).heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(warning.first, color = Color(0xFFAA0000), fontSize = (if (elder) 32 else 26).sp)
                            if (warning.second.isNotEmpty()) Text(app.getString(R.string.standby_overlay_suffix, warning.second), fontSize = (if (elder) 28 else 22).sp)
                            Text(app.getString(R.string.standby_advice), fontSize = (if (elder) 28 else 22).sp)
                            Button(onClick = { dismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                                Text(app.getString(R.string.standby_overlay_ack), fontSize = (if (elder) 28 else 22).sp)
                            }
                            TextButton(onClick = {
                                dismiss()
                                app.startActivity(Intent(app, StandbyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text(app.getString(R.string.standby_overlay_details)) }
                        }
                    } }
                }
                owner = service
            }
            main.postDelayed(this, 250)
        }
    }
    private fun password(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        nodes.add(node)
        var checked = 0
        while (nodes.isNotEmpty()) {
            if (++checked > 512) return true // Fail closed on unusually large trees.
            val current = nodes.removeFirst()
            if (current.isPassword) return true
            if (current.childCount + nodes.size > 512) return true
            for (i in 0 until current.childCount) current.getChild(i)?.let(nodes::addLast)
        }
        return false
    }

}

internal object StandbyOverlayPolicy {
    fun allowed(interactive: Boolean, locked: Boolean, available: Boolean, sensitive: Boolean) =
        interactive && !locked && available && !sensitive
    fun sensitive(pkg: String): Boolean = pkg.isEmpty() || listOf("systemui", "dialer", "incall", "telecom", "phone", "permissioncontroller", "credential", "authenticator").any { pkg.lowercase().contains(it) }
}
