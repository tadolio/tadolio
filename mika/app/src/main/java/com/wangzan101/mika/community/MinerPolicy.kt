// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import java.net.URI

internal object MinerPolicy {
    const val MAX_DAILY_TOKENS = 1_000_000_000L
    const val DEFAULT_MIN_BATTERY = 20
    const val DEFAULT_WIFI_ONLY = false
    const val DEFAULT_CHARGING_ONLY = false
    const val DEFAULT_AUTO_START = true
    const val DEFAULT_DAILY_TOKENS = 2_000_000L
    fun initialLimit(saved: String) = saved.ifBlank { DEFAULT_DAILY_TOKENS.toString() }
    /** Price estimates and a confirmation checkbox are not prerequisites for mining. */
    fun canEnable(base: String, model: String, key: String, limit: Long?, battery: Int?, number: String) =
        battery != null && battery in 0..100 && number.matches(Regex("[0-9]{8}")) &&
            validApi(base, model, key, limit, true)
    fun validApi(base: String, model: String, key: String, dailyLimit: Long?, metered: Boolean): Boolean =
        runCatching {
            val u = URI(base)
            metered && dailyLimit != null && dailyLimit in 1..MAX_DAILY_TOKENS && key.isNotBlank() &&
                model.isNotBlank() && u.scheme == "https" && !u.host.isNullOrBlank() &&
                u.userInfo == null && u.query == null && u.fragment == null &&
                u.host.lowercase() !in setOf("chatgpt.com", "chat.openai.com", "grok.com", "sora.com") &&
                !u.host.lowercase().endsWith(".chatgpt.com") && !u.host.lowercase().endsWith(".grok.com")
        }.getOrDefault(false)
    fun canAccept(online: Boolean, wifi: Boolean, charging: Boolean, battery: Int,
                  wifiOnly: Boolean, chargingOnly: Boolean, used: Long, limit: Long, minBattery: Int = DEFAULT_MIN_BATTERY) =
        online && (!wifiOnly || wifi) && (!chargingOnly || charging) && (charging || battery >= minBattery) && used < limit
    fun day(at: Long) = Math.floorDiv(at + 8 * 3_600_000L, 86_400_000L)
}
