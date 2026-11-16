// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.wangzan101.mika.settings.SystemStatusMonitor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.GuidedSetup
import kotlinx.coroutines.delay
import java.util.Locale

/** First-run local setup. The existing checklist remains the repair/USB fallback. */
@Composable
internal fun GuidedPhoneSetup(onFix: (Fix) -> Unit, onLegacy: () -> Unit, onComplete: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val highlight = remember { SetupHighlightOverlay() }
    val phone = remember { PhoneServices.of(context) }
    val monitor = remember { SystemStatusMonitor(context, phone.shellControl) }
    var pairingSelected by rememberSaveable { mutableStateOf(false) }
    val route = GuidedSetup.route(ScreenAccessibility.instance.value != null, Build.VERSION.SDK_INT, pairingSelected)
    var codeIntroDone by rememberSaveable { mutableStateOf(false) }
    var notificationsSkipped by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var accessibilityTimedOut by rememberSaveable { mutableStateOf(false) }
    var unblockReturned by rememberSaveable { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf<GuidedSetup.Step?>(null) }
    var helperSince by remember { mutableStateOf<Long?>(null) }
    var wifi by remember { mutableStateOf(true) }
    var foreground by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf(GuidedSetup.State()) }
    var stalled by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val step = GuidedSetup.step(state.copy(codeIntroDone = codeIntroDone,
        notificationsSkipped = notificationsSkipped), route)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!pairingSelected) notificationsSkipped = true
        if (granted && pairingSelected) phone.pairing.begin()
    }
    val fix = rememberUpdatedState(onFix)
    val settings = remember {
        runCatching { SystemPickers.settingsPackage(context.packageManager)?.takeIf {
            val info = context.packageManager.getApplicationInfo(it, 0)
            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } }.getOrNull()
    }
    fun sample(): GuidedSetup.State = GuidedSetup.State(
        developer = runCatching { DeveloperSettings.developerOptions(context.contentResolver) }.getOrDefault(false),
        accessibility = ScreenAccessibility.instance.value != null,
        wireless = runCatching { DeveloperSettings.wirelessDebugging(context.contentResolver) }.getOrDefault(false),
        helper = phone.shellControl.status.value == BuiltinShellStatus.Running,
        notifications = Build.VERSION.SDK_INT < 33 || NotificationManagerCompat.from(context).areNotificationsEnabled(),
        restricted = attempted && (monitor.restrictedSettings() || accessibilityTimedOut),
        helperWaitExpired = helperSince?.let { SystemClock.elapsedRealtime() - it >= 10_000 } == true,
    )
    fun openStep(s: GuidedSetup.Step) {
        opened = s
        when (s) {
            GuidedSetup.Step.Unrestrict -> fix.value(Fix.OpenAppInfo)
            GuidedSetup.Step.Developer -> fix.value(Fix.OpenAboutPhone)
            GuidedSetup.Step.Accessibility -> {
                fix.value(Fix.OpenAccessibility)
                // No overlay is possible before this switch is on; a toast still shows above Settings.
                android.widget.Toast.makeText(context, R.string.setup_accessibility_toast, android.widget.Toast.LENGTH_LONG).show()
            }
            GuidedSetup.Step.Wireless -> fix.value(Fix.OpenWirelessDebugging)
            GuidedSetup.Step.Pair -> { phone.pairing.begin(); fix.value(Fix.OpenWirelessDebugging) }
            GuidedSetup.Step.Notifications -> {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else fix.value(Fix.AllowNotifications)
            }
            else -> Unit
        }
        val toast = when (s) {
            GuidedSetup.Step.Unrestrict -> R.string.t60_unrestrict_toast
            GuidedSetup.Step.Developer -> R.string.t60_developer_toast
            GuidedSetup.Step.Wireless -> R.string.t60_wireless_toast
            GuidedSetup.Step.Pair -> R.string.t60_pair_toast
            else -> null
        }
        toast?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { foreground = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        foreground = true
        if (opened == GuidedSetup.Step.Accessibility) attempted = true
        val now = sample()
        if (opened == GuidedSetup.Step.Unrestrict && !now.accessibility) {
            unblockReturned = now.restricted
            if (!monitor.restrictedSettings()) accessibilityTimedOut = false
        }
        if (opened == GuidedSetup.Step.Notifications && !pairingSelected) notificationsSkipped = true
        state = sample()
        opened = null
    }
    // Live polling continues while the user is in Settings; no task/model is created.
    LaunchedEffect(Unit) {
        var hadService = ScreenAccessibility.instance.value != null
        while (true) {
            val now = sample()
            if (now.helper && helperSince == null) helperSince = SystemClock.elapsedRealtime()
            if (!now.helper) helperSince = null
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            wifi = connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            val previous = state
            state = now
            if (!now.restricted) accessibilityTimedOut = false
            if (!now.accessibility && opened == GuidedSetup.Step.Accessibility && now.restricted && foreground) state = sample()
            if (!foreground && opened != null &&
                ((!previous.developer && now.developer) || (!previous.wireless && now.wireless) ||
                    (previous.restricted && !now.restricted) || (!previous.helper && now.helper))) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            // Steps do not advance while Settings covers Tadolio, so come back as soon as the switch is on.
            if (!hadService && now.accessibility) context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            hadService = now.accessibility
            delay(800)
        }
    }
    DisposableEffect(Unit) { onDispose { highlight.clear(); phone.pairing.end() } }
    LaunchedEffect(step, retry, wifi) {
        highlight.clear()
        try {
            stalled = false
            password = false
            if (step == GuidedSetup.Step.Done) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                android.widget.Toast.makeText(context, R.string.t60_all_set, android.widget.Toast.LENGTH_SHORT).show()
                onComplete()
                return@LaunchedEffect
            }
            if (step == GuidedSetup.Step.Helper) return@LaunchedEffect
            // System pages before Accessibility are opened only by the person's button.
            val automatic = state.accessibility && step in setOf(GuidedSetup.Step.Developer, GuidedSetup.Step.Wireless, GuidedSetup.Step.Pair)
            if (automatic && wifi && opened != step) openStep(step)
            if (!automatic && retry == 0) return@LaunchedEffect
            if (!wifi && step in setOf(GuidedSetup.Step.Wireless, GuidedSetup.Step.Pair)) return@LaunchedEffect
            var elapsed = 0
            var lastTree: List<GuidedSetup.Node>? = null
            var failedTree: List<GuidedSetup.Node>? = null
            var clicks = 0
            var scrolls = 0
            var manualTicks = 0
            while (elapsed < 45_000) {
                delay(800)
                if (settings == null) { elapsed += 800; continue }
                // Never inspect or act while the device is locked, including a hidden secure window.
                if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    highlight.clear()
                    password = true
                    continue
                }
                val service = ScreenAccessibility.instance.value
                if (service == null) { highlight.clear(); elapsed += 800; continue }
                elapsed += 800
                val nodes = runCatching {
                    val root = service.windowsOn(Display.DEFAULT_DISPLAY).firstOrNull { it.isActive }?.root
                    if (root != null && root.packageName?.toString() == settings) setupNodes(root) else emptyList()
                }.getOrDefault(emptyList())
                val tree = nodes.map { (node, click) -> GuidedSetup.Node(
                    node.text?.toString().orEmpty(), click != null,
                    if (node.isCheckable) node.isChecked else null, node.isPassword, node.isScrollable && node.isEnabled,
                ) }
                val action = GuidedSetup.action(step, tree, nodes.isNotEmpty(), scrolls, Build.MANUFACTURER)
                if (action == GuidedSetup.Action.Password) { highlight.clear(); password = true; elapsed -= 800; continue }
                if (tree == lastTree && action is GuidedSetup.Action.Click) {
                    val buildLabel = tree[action.index].text.trim().lowercase(Locale.ROOT)
                    if (clicks >= if (buildLabel in setOf("build number", "\u7248\u672c\u53f7", "\u7248\u672c\u865f")) 7 else 1) failedTree = tree
                }
                val bounds = nodes.map { (node, _) -> android.graphics.Rect().also(node::getBoundsInScreen).let {
                    GuidedSetup.Bounds(it.left, it.top, it.right, it.bottom)
                } }
                highlight.show(service, GuidedSetup.highlights(step, tree, bounds, nodes.isNotEmpty(), Build.MANUFACTURER, clickFailed = tree == failedTree))
                password = false
                if (action is GuidedSetup.Action.Scroll) {
                    val moved = runCatching { nodes[action.index].first.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }.getOrDefault(false)
                    scrolls++
                    if (!moved) scrolls = 6
                    continue
                }
                // Unknown pages yield to the person after about ten seconds. Battery and notification pages wait for
                // the person's own tap, and the pairing dialog must stay open while the code is read.
                if (action == GuidedSetup.Action.Manual && nodes.isNotEmpty() &&
                    step in setOf(GuidedSetup.Step.Developer, GuidedSetup.Step.Wireless, GuidedSetup.Step.Pair)) {
                    manualTicks++
                    if (manualTicks >= 12) break
                } else manualTicks = 0
                if (action is GuidedSetup.Action.Click) {
                    // Only build-number activation requires repeated clicks; every other unchanged tree is clicked once.
                    val build = tree[action.index].text.lowercase(Locale.ROOT) in setOf("build number", "版本号", "版本號")
                    if (tree != lastTree) { lastTree = tree; clicks = 0; scrolls = 0 }
                    if (clicks < if (build) 7 else 1) {
                        val clicked = runCatching { nodes[action.index].second?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }.getOrDefault(false)
                        if (!clicked) failedTree = tree
                        if (!clicked) highlight.show(service, GuidedSetup.highlights(step, tree, bounds,
                            nodes.isNotEmpty(), Build.MANUFACTURER, clickFailed = true))
                        clicks++
                    }
                }
            }
            stalled = true
            if (step == GuidedSetup.Step.Accessibility && !state.accessibility) {
                attempted = true
                accessibilityTimedOut = true
                state = sample()
            }
            // With no service we cannot know whether a credentials confirmation page is covering Settings.
            // Leave the manual page in place rather than interrupting a password entry.
            if (ScreenAccessibility.instance.value != null && !password &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        } finally { highlight.clear() }
    }
    val done = run {
        // Mark completion only from actual system state, never from a click or a spoken assertion.
        buildSet {
            if (state.developer) add(GuidedSetup.Step.Developer)
            if (state.accessibility) add(GuidedSetup.Step.Accessibility)
            if (state.wireless) add(GuidedSetup.Step.Wireless)
            if (state.helper) { add(GuidedSetup.Step.Pair); add(GuidedSetup.Step.Helper) }
            if (state.notifications) add(GuidedSetup.Step.Notifications)
        }
    }
    LaunchedEffect(step, state.notifications) {
        if (step == GuidedSetup.Step.CodeIntro && state.notifications) phone.pairing.begin()
    }
    val automatic = state.accessibility && !stalled && step in setOf(GuidedSetup.Step.Developer, GuidedSetup.Step.Wireless, GuidedSetup.Step.Pair)
    val needsWifi = !wifi && step in setOf(GuidedSetup.Step.Wireless, GuidedSetup.Step.Pair)
    val titleId = when {
        needsWifi -> R.string.t60_wifi_title
        password -> R.string.t60_password_title
        automatic -> R.string.t60_auto_title
        else -> when (step) {
            GuidedSetup.Step.Accessibility -> R.string.t60_accessibility_title
            GuidedSetup.Step.Unrestrict -> R.string.t60_unrestrict_title
            GuidedSetup.Step.CodeIntro -> R.string.t60_code_title
            GuidedSetup.Step.Developer -> R.string.t60_developer_title
            GuidedSetup.Step.Wireless -> R.string.t60_wireless_title
            GuidedSetup.Step.Pair -> R.string.t60_pair_title
            GuidedSetup.Step.Helper -> R.string.t60_helper_title
            else -> R.string.t60_notifications_title
        }
    }
    val bodyId = when {
        needsWifi -> R.string.t60_wifi_body
        password -> R.string.t60_password_body
        automatic -> R.string.t60_auto_body
        else -> when (step) {
            GuidedSetup.Step.Accessibility -> R.string.t60_accessibility_body
            GuidedSetup.Step.Unrestrict -> R.string.t60_unrestrict_body
            GuidedSetup.Step.CodeIntro -> R.string.t60_code_body
            GuidedSetup.Step.Developer -> R.string.t60_developer_body
            GuidedSetup.Step.Wireless -> R.string.t60_wireless_body
            GuidedSetup.Step.Pair -> R.string.t60_pair_body
            GuidedSetup.Step.Helper -> R.string.t60_helper_body
            else -> if (pairingSelected) R.string.t60_notifications_pair_body else R.string.t60_notifications_body
        }
    }
    fun alternate() { pairingSelected = true; retry = 0 }
    fun proceed() {
        if (needsWifi) { fix.value(Fix.OpenWifi); return }
        if (step == GuidedSetup.Step.Unrestrict && unblockReturned) { alternate(); return }
        if (step == GuidedSetup.Step.CodeIntro) {
            if (!state.notifications) { codeIntroDone = true; openStep(GuidedSetup.Step.Notifications); return }
            phone.pairing.begin()
            codeIntroDone = true
            return
        }
        openStep(step)
        retry++
    }
    if (step == GuidedSetup.Step.Done) return
    val primaryId = when {
        needsWifi -> R.string.t60_wifi_open
        step == GuidedSetup.Step.Unrestrict -> if (unblockReturned) R.string.t60_alternate else R.string.t60_unblock
        step == GuidedSetup.Step.CodeIntro -> R.string.t60_code_start
        step == GuidedSetup.Step.Developer -> R.string.t60_about_open
        step == GuidedSetup.Step.Pair -> R.string.t60_pair_open
        step == GuidedSetup.Step.Notifications -> R.string.t60_notifications_allow
        else -> R.string.t60_open
    }
    val shareTitle = stringResource(R.string.t60_family)
    val progress = GuidedSetup.share(step, done)
    val diagnostics = "Android ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\n" +
        "restricted=${state.restricted}, wifi=$wifi, helper=${state.helper}, accessibility=${state.accessibility}\n" + progress
    SetupPage(
        title = stringResource(titleId), body = stringResource(bodyId),
        primary = if (automatic || step == GuidedSetup.Step.Helper || password) null else stringResource(primaryId),
        onPrimary = ::proceed,
        notice = when (step) {
            GuidedSetup.Step.Accessibility -> stringResource(R.string.t60_accessibility_notice)
            GuidedSetup.Step.CodeIntro -> if (state.notifications) stringResource(R.string.t60_code_preview) else null
            else -> null
        },
        steps = when {
            automatic -> stringResource(R.string.t60_auto_steps)
            step == GuidedSetup.Step.Accessibility -> stringResource(R.string.t60_accessibility_steps)
            step == GuidedSetup.Step.Unrestrict -> stringResource(R.string.t60_unrestrict_steps)
            step == GuidedSetup.Step.Developer -> stringResource(when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
                "oppo", "realme", "oneplus" -> R.string.t60_developer_oppo
                "samsung" -> R.string.t60_developer_samsung
                else -> R.string.t60_developer_steps
            })
            else -> null
        },
        content = {
            val status by phone.shellControl.status.collectAsState()
            if (step == GuidedSetup.Step.CodeIntro) {
                val icons = listOf(com.wangzan101.mika.ui.theme.MikaIcons.Phone, com.wangzan101.mika.ui.theme.MikaIcons.Bell, com.wangzan101.mika.ui.theme.MikaIcons.Key)
                stringResource(R.string.t60_code_steps).split(" → ").forEachIndexed { index, label ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Icon(icons[index], null)
                        Text(label)
                    }
                }
            }
            if (step == GuidedSetup.Step.Pair && status is BuiltinShellStatus.Failed) {
                val failure = (status as BuiltinShellStatus.Failed).reason
                Text(when (failure) {
                    com.wangzan101.mika.core.shell.BuiltinShellFailure.PairingWindowClosed -> stringResource(R.string.t60_pair_closed)
                    com.wangzan101.mika.core.shell.BuiltinShellFailure.WrongCode -> stringResource(R.string.t60_pair_error)
                    else -> PhoneSetupCopy.failure(failure).body
                })
            }
        },
        links = {
            if (step == GuidedSetup.Step.Unrestrict && !unblockReturned) TextButton(::alternate) { Text(stringResource(R.string.t60_alternate)) }
            TextButton({ onClose() }) { Text(stringResource(R.string.setup_conversation_later)) }
        },
        detailsLabel = stringResource(R.string.t60_stuck),
        details = { close ->
            Text(stringResource(when (step) {
                GuidedSetup.Step.Unrestrict -> R.string.t60_unrestrict_explanation
                GuidedSetup.Step.CodeIntro -> R.string.t60_code_explanation
                GuidedSetup.Step.Notifications -> R.string.t60_notifications_explanation
                else -> R.string.t60_help_explanation
            }))
            TextButton({ close(); proceed() }) { Text(stringResource(R.string.setup_conversation_retry)) }
            TextButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, progress), shareTitle)) }) { Text(shareTitle) }
            TextButton({ close(); onLegacy() }) { Text(stringResource(R.string.t60_usb)) }
            TextButton({
                close()
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Tadolio", diagnostics))
            }) { Text(stringResource(R.string.t60_copy_diagnostics)) }
            if (step == GuidedSetup.Step.Pair) {
                Text(stringResource(R.string.t60_manual_code_note))
                ManualCode(askPort = true) { code, port -> phone.pairing.submit(code, port) }
            }
        },
    )
}

/** Flatten at most 400 nodes, retaining the nearest clickable ancestor within this settings tree. */
private fun setupNodes(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo?>> {
    val out = mutableListOf<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo?>>()
    val queue = ArrayDeque(listOf(root to null as AccessibilityNodeInfo?))
    var visited = 0
    while (queue.isNotEmpty() && visited++ < 400) {
        val (node, ancestor) = queue.removeFirst()
        if (!node.isVisibleToUser || !node.isEnabled) continue
        val click = if (node.isClickable && node.isEnabled) node else ancestor
        out += node to click
        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to click) }
    }
    return out
}
