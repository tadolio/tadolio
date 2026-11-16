// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.settings.SelfConfig

/**
 * 模型能改的那几项设置。[FeatureFlags] 是存储,这里只挑可逆的偏好交给模型:
 *
 * **不给的**:模型与 Key(换掉自己的脑子、花用户的钱)、任何删除数据(清工作区、重置运行环境、删对话)——不可逆。
 * **也不给**:容器的联网与代理——打开后数据可能离开手机,这道线要用户自己在设置里决定。
 * 给的都能随手改回去。
 */
class SelfConfigImpl(
    private val flags: FeatureFlags,
    private val context: android.content.Context? = null,
    private val localTask: Boolean = false,
    private val numberSettings: com.wangzan101.mika.number.NumberPreferences? =
        context?.let { com.wangzan101.mika.number.NumberClient.get(it).settings },
) : SelfConfig {
    override fun options(): List<SelfConfig.Option> = listOf(
        SelfConfig.Option(KEY_PACE, "操作节奏", flags.operatingPace().name.lowercase(), listOf("fast", "standard", "human")),
        SelfConfig.Option(KEY_MUTE, "干活时把被操作的 App 静音", onOff(flags.muteTarget()), ON_OFF),
        SelfConfig.Option(KEY_THEME, "外观", flags.themeMode().name.lowercase(), listOf("system", "light", "dark")),
        SelfConfig.Option(KEY_ACCENT, "强调色", flags.themeAccent().name.lowercase(), ACCENTS),
        SelfConfig.Option(KEY_CAPSULE, "悬浮胶囊", flags.capsuleMode().name.lowercase(), listOf("off", "duringtasks", "always")),
        SelfConfig.Option(KEY_KEEP_ON, "干活时屏幕常亮", onOff(flags.keepScreenOn()), ON_OFF),
    ) + listOf(SelfConfig.Option("large_text", "大字号", onOff(flags.largeText()), ON_OFF)) +
        (context?.let { c -> listOf(
            SelfConfig.Option("elder_mode", "长辈模式", onOff(com.wangzan101.mika.family.ElderStore.get(c).enabled.value), ON_OFF),
        ) + if (localTask) listOf(SelfConfig.Option("standby", "随时待命（新短信自动查骗）", onOff(com.wangzan101.mika.family.StandbyStore.get(c).enabled()), ON_OFF)) else emptyList() } ?: emptyList()) +
        (numberSettings?.let { settings -> listOf(
            SelfConfig.Option("number_public_card", context?.getString(com.wangzan101.mika.R.string.number_public_card)
                ?: "number_public_card", settings.publicCard()),
            SelfConfig.Option("number_auto_reply", context?.getString(com.wangzan101.mika.R.string.number_auto_reply)
                ?: "number_auto_reply", onOff(settings.autoReplyEnabled()), ON_OFF),
            SelfConfig.Option("governance_jury", context?.getString(com.wangzan101.mika.R.string.governance_jury)
                ?: "Community voting", onOff(settings.governanceJuryEnabled()), ON_OFF),
            SelfConfig.Option("post_standby", context?.getString(com.wangzan101.mika.R.string.post_standby)
                ?: "Let my agent watch new posts", onOff(settings.postStandbyEnabled()), ON_OFF),
        ) } ?: emptyList())

    override fun set(key: String, value: String): String? {
        val v = value.trim().lowercase()
        fun onOff(): Boolean? = when (v) {
            "on", "true", "yes" -> true
            "off", "false", "no" -> false
            else -> null
        }
        fun bool(write: (Boolean) -> Unit): String? {
            val on = onOff() ?: return ON_OFF_HINT
            write(on)
            return null
        }
        return when (key) {
            "number_public_card" -> {
                if (numberSettings == null) return "there is no such setting"
                if (value.codePointCount(0, value.length) > 1000) return context?.getString(com.wangzan101.mika.R.string.number_public_card_hint)
                    ?: "number_public_card: max 1000"
                numberSettings.setPublicCard(value)
                null
            }
            "number_auto_reply" -> if (numberSettings != null) bool {
                numberSettings.setAutoReplyEnabled(it)
            } else "there is no such setting"
            "governance_jury" -> if (numberSettings != null) bool {
                numberSettings.setGovernanceJuryEnabled(it)
            } else "there is no such setting"
            "post_standby" -> if (numberSettings != null) bool {
                numberSettings.setPostStandbyEnabled(it)
            } else "there is no such setting"
            "large_text" -> bool { flags.setLargeText(it) }
            "elder_mode" -> if (context != null) bool { com.wangzan101.mika.family.ElderStore.get(context).setEnabled(it) } else "there is no such setting"
            "standby" -> if (localTask && context != null) bool { com.wangzan101.mika.family.StandbyStore.get(context).setChecking(it) } else "there is no such setting"
            KEY_PACE -> {
                val pace = OperatingPace.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "operating pace is one of fast, standard, human"
                flags.setOperatingPace(pace)
                null
            }
            KEY_MUTE -> bool { flags.setMuteTarget(it) }
            KEY_THEME -> {
                val mode = ThemeMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "appearance is one of system, light, dark"
                flags.setThemeMode(mode)
                null
            }
            KEY_ACCENT -> {
                val accent = ThemeAccent.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "accent color is one of ${ACCENTS.joinToString(", ")}"
                flags.setThemeAccent(accent)
                null
            }
            KEY_CAPSULE -> {
                val mode = com.wangzan101.mika.capsule.CapsuleMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "capsule is one of off, duringtasks, always"
                flags.setCapsuleMode(mode)
                null
            }
            KEY_KEEP_ON -> bool { flags.setKeepScreenOn(it) }
            else -> "there is no such setting"
        }
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private companion object {
        val ON_OFF = listOf("on", "off")
        const val ON_OFF_HINT = "value is on or off"
        const val KEY_PACE = "operating_pace"
        const val KEY_MUTE = "mute_apps"
        const val KEY_THEME = "appearance"
        const val KEY_ACCENT = "accent_color"
        val ACCENTS = ThemeAccent.entries.map { it.name.lowercase() }
        const val KEY_CAPSULE = "capsule"
        const val KEY_KEEP_ON = "keep_screen_on"
    }
}
