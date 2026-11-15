// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
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
    val route = remember { GuidedSetup.route(ScreenAccessibility.instance.value != null, Build.VERSION.SDK_INT) }
    var state by remember { mutableStateOf(GuidedSetup.State()) }
    var stalled by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var problems by rememberSaveable { mutableStateOf(false) }
    val step = GuidedSetup.step(state, route)
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
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    )
    fun openStep(s: GuidedSetup.Step) {
        when (s) {
            GuidedSetup.Step.Developer -> fix.value(Fix.OpenAboutPhone)
            GuidedSetup.Step.Accessibility -> {
                fix.value(Fix.OpenAccessibility)
                // No overlay is possible before this switch is on; a toast still shows above Settings.
                android.widget.Toast.makeText(context, R.string.setup_accessibility_toast, android.widget.Toast.LENGTH_LONG).show()
            }
            GuidedSetup.Step.Wireless -> fix.value(Fix.OpenWirelessDebugging)
            GuidedSetup.Step.Pair -> { phone.pairing.begin(); fix.value(Fix.OpenWirelessDebugging) }
            GuidedSetup.Step.Notifications -> fix.value(Fix.AllowNotifications)
            GuidedSetup.Step.Battery -> runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            else -> Unit
        }
    }
    // Live polling continues while the user is in Settings; no task/model is created.
    LaunchedEffect(Unit) {
        var hadService = ScreenAccessibility.instance.value != null
        while (true) {
            val now = sample()
            state = now
            // Steps do not advance while Settings covers Tadolio, so come back as soon as the switch is on.
            if (enabled && !hadService && now.accessibility) context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            hadService = now.accessibility
            delay(800)
        }
    }
    DisposableEffect(Unit) { onDispose { highlight.clear(); phone.pairing.end() } }
    LaunchedEffect(step, enabled, retry) {
        highlight.clear()
        try {
            stalled = false
            password = false
            if (!enabled) return@LaunchedEffect
            if (step == GuidedSetup.Step.Done) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                onComplete()
                return@LaunchedEffect
            }
            // Give the local spoken explanation time before opening the system page.
            delay(if (step == GuidedSetup.Step.Accessibility) 7000 else 1800)
            openStep(step)
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
            if (state.battery) add(GuidedSetup.Step.Battery)
        }
    }
    val instruction = stringResource(when (step) {
        GuidedSetup.Step.Developer -> if (stalled || !state.accessibility) R.string.setup_conversation_developer_manual else R.string.setup_conversation_developer
        GuidedSetup.Step.Accessibility -> R.string.setup_conversation_accessibility
        GuidedSetup.Step.Wireless -> if (stalled || !state.accessibility) R.string.setup_conversation_wireless_manual else R.string.setup_conversation_wireless
        GuidedSetup.Step.Pair -> if (state.accessibility && !stalled) R.string.setup_conversation_pair_auto else R.string.setup_conversation_pair_manual
        GuidedSetup.Step.Notifications -> R.string.setup_conversation_notifications
        GuidedSetup.Step.Battery -> R.string.setup_conversation_battery
        GuidedSetup.Step.Done -> R.string.setup_conversation_done
        GuidedSetup.Step.Helper -> R.string.setup_conversation_pair_auto
    })
    val message = when {
        // Already paired and only the wireless switch is off (typically after a reboot): one tap, not "a few switches".
        !enabled && step == GuidedSetup.Step.Wireless && state.accessibility && phone.shellControl.paired ->
            stringResource(R.string.setup_conversation_intro_wireless_only)
        !enabled -> stringResource(R.string.setup_conversation_intro)
        password -> stringResource(R.string.setup_conversation_password)
        stalled -> stringResource(R.string.setup_conversation_stalled) + "\n" + instruction
        else -> instruction
    }
    val firstExample = if (step == GuidedSetup.Step.Done) "\n" + ChatCopy.setupSocialPrompt() else ""
    val shareTitle = stringResource(R.string.setup_conversation_share)
    fun proceed() {
        if (!enabled) enabled = true
        else if (step == GuidedSetup.Step.Done) onComplete()
        else retry++
    }
    OnboardingConversation(message + firstExample, onReply = {
        // Speech selects only local controls; it cannot assert that a permission was granted.
        if (onboardingAnswer(it) == OnboardingAnswer.Start) proceed()
    }) {
        TextButton(onClick = ::proceed) {
            Text(stringResource(if (step == GuidedSetup.Step.Done && enabled) R.string.setup_conversation_finish else if (enabled) R.string.setup_conversation_retry else R.string.setup_conversation_continue))
        }
        if (enabled) TextButton(onClick = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, GuidedSetup.share(step, done)), shareTitle))
        }) { Text(shareTitle) }
        if (enabled) TextButton(onClick = { problems = !problems }) {
            Text(stringResource(R.string.setup_conversation_problems))
        }
        if (enabled && problems) TextButton(onClick = { enabled = false; onLegacy() }) {
            Text(stringResource(R.string.setup_conversation_manual))
        }
        TextButton(onClick = { highlight.clear(); onClose() }) { Text(stringResource(R.string.setup_conversation_later)) }
    }
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
