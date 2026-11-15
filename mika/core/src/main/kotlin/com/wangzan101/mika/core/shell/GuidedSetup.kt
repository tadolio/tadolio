// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.shell

import java.util.Locale

/** Local setup decisions. No model, task text or network input is accepted. */
object GuidedSetup {
    enum class Route { AccessibilityFirst, PairingFirst }
    enum class Step { Accessibility, Developer, Wireless, Pair, Helper, Notifications, Battery, Done }
    data class State(
        val developer: Boolean = false,
        val accessibility: Boolean = false,
        val wireless: Boolean = false,
        val helper: Boolean = false,
        val notifications: Boolean = false,
        val battery: Boolean = false,
    )

    // Always try accessibility first. PairingFirst remains an explicit restricted-settings fallback.
    fun route(accessibility: Boolean, sdk: Int): Route =
        Route.AccessibilityFirst

    fun step(s: State, route: Route): Step = when {
        !s.accessibility && (route == Route.AccessibilityFirst || s.helper) -> Step.Accessibility
        !s.helper && !s.developer -> Step.Developer
        !s.helper && !s.wireless -> Step.Wireless
        !s.helper -> Step.Pair
        !s.accessibility -> Step.Accessibility
        !s.notifications -> Step.Notifications
        !s.battery -> Step.Battery
        else -> Step.Done
    }

    data class Node(val text: String, val clickable: Boolean = false, val checked: Boolean? = null,
                    val password: Boolean = false, val scrollable: Boolean = false)
    sealed interface Action {
        data class Click(val index: Int) : Action
        data class Scroll(val index: Int) : Action
        data object Password : Action
        data object Manual : Action
    }

    /** Exact labels on a verified system-settings window only; unknown layouts fail closed. */
    fun action(step: Step, nodes: List<Node>, systemSettings: Boolean, scrolls: Int = 0, manufacturer: String = ""): Action {
        if (!systemSettings) return Action.Manual
        val text = nodes.map { it.text.trim().lowercase(Locale.ROOT) }
        if (nodes.any { it.password } || text.any { it in passwords }) return Action.Password
        if (step == Step.Wireless && nodes.any { it.checked == true }) return Action.Manual
        val labels = when (step) {
            Step.Developer -> setOf("software information", "软件信息", "軟體資訊", "build number", "版本号", "版本號")
            Step.Wireless -> setOf("wireless debugging", "无线调试", "無線偵錯")
            Step.Pair -> setOf("pair device with pairing code", "使用配对码配对设备", "使用配對碼配對裝置")
            // Runtime permission, battery consent and accessibility are always human checkpoints.
            else -> emptySet()
        }
        // An already checked switch must never be toggled off during a delayed state refresh.
        // Some phones open the developer list, not the wireless page, so pairing has to enter that page first.
        val entry = if (step == Step.Pair) setOf("wireless debugging", "\u65e0\u7ebf\u8c03\u8bd5", "\u7121\u7dda\u5075\u932f") else emptySet()
        val allowed = if (step == Step.Developer && manufacturer.lowercase(Locale.ROOT) in setOf("oppo", "realme", "oneplus"))
            labels + setOf("\u7248\u672c", "\u7248\u672c\u4fe1\u606f", "version", "version information") else labels + entry
        val candidates = nodes.indices.filter { nodes[it].clickable && nodes[it].checked != true && text[it] in allowed }
        // On the wireless page its title or switch can match too; the pairing row always wins.
        candidates.filter { text[it] !in entry }.singleOrNull()?.let { return Action.Click(it) }
        if (candidates.size == 1 && text.none { it in labels }) return Action.Click(candidates.single())
        if (candidates.isNotEmpty() || text.any { it in allowed }) return Action.Manual
        if (step in setOf(Step.Developer, Step.Wireless, Step.Pair) && scrolls < 6) {
            nodes.indices.filter { nodes[it].scrollable }.singleOrNull()?.let { return Action.Scroll(it) }
        }
        return Action.Manual
    }

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    enum class HintText { Tap, BuildSeven }
    data class Highlight(val bounds: Bounds, val text: HintText)

    /** Screen coordinates and fixed copy only; never guess a missing target. */
    fun highlights(step: Step, nodes: List<Node>, bounds: List<Bounds>, systemSettings: Boolean,
                   manufacturer: String = "", clickFailed: Boolean = false): List<Highlight> {
        if (!systemSettings || nodes.size != bounds.size ||
            action(step, nodes, true, manufacturer = manufacturer) == Action.Password) return emptyList()
        val manual = action(step, nodes, true, manufacturer = manufacturer) == Action.Manual
        if (!manual && !clickFailed) return emptyList()
        val appNames = setOf("hivo", "Tadolio")
        val consent = setOf("allow", "允许", "允許", "zulassen")
        return nodes.indices.mapNotNull { i ->
            val n = nodes[i]
            val label = n.text.trim().lowercase(Locale.ROOT)
            val known = when (step) {
                Step.Battery -> label in appNames || label in consent
                Step.Notifications -> n.checked == false && (nodes.count { it.checked != null } == 1 ||
                    label in setOf("allow notifications", "\u5141\u8bb8\u901a\u77e5", "\u5141\u8a31\u901a\u77e5"))
                else -> action(step, listOf(n.copy(clickable = true)), true,
                    manufacturer = manufacturer) is Action.Click
            }
            val b = bounds[i]
            if (!known || n.checked == true || b.left < 0 || b.top < 0 || b.right <= b.left || b.bottom <= b.top) null
            else Highlight(b, if (label in setOf("build number", "版本号", "版本號")) HintText.BuildSeven else HintText.Tap)
        }.distinct()
    }

    private val passwords = setOf("enter pin", "confirm your pin", "enter password", "confirm your password",
        "输入密码", "输入 pin 码", "确认您的 pin 码", "確認您的 pin 碼", "输入锁屏密码", "輸入密碼")

    /** Progress only: never interpolate a screen label, pairing code, device ID or user input. */
    fun share(step: Step, done: Set<Step>): String = Step.entries.filter { it != Step.Done }.joinToString("\n") {
        "${it.ordinal + 1} ${it.name} ${if (it in done) "✓" else if (it == step) "✗" else "…"}"
    }
}
