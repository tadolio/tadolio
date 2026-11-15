// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.R
import java.math.BigDecimal

internal object MinerLimits {
    val presets = listOf(500_000L, 2_000_000L, 5_000_000L)
    // A phone task typically uses 30,000–50,000 tokens. Use the midpoint for this rough guide.
    const val TOKENS_PER_TASK = 40_000L
    fun jobs(limit: Long?) = (limit ?: 0L) / TOKENS_PER_TASK
    fun unit(chinese: Boolean) = if (chinese) 10_000L else 1_000_000L
    fun display(tokens: Long, chinese: Boolean): String =
        BigDecimal.valueOf(tokens).divide(BigDecimal.valueOf(unit(chinese))).stripTrailingZeros().toPlainString()
    fun parse(value: String, chinese: Boolean): Long? = runCatching {
        value.trim().toBigDecimal().multiply(BigDecimal.valueOf(unit(chinese))).longValueExact()
            .takeIf { it in 1..MinerPolicy.MAX_DAILY_TOKENS }
    }.getOrNull()
}

internal enum class MinerState(val message: Int) {
    WAITING_EXAM(R.string.miner_state_waiting_exam),
    EXAM(R.string.miner_state_exam),
    ONLINE(R.string.miner_state_online),
    WORKING(R.string.miner_state_working),
    LIMIT(R.string.miner_state_limit),
    BATTERY(R.string.miner_state_paused_battery),
    NETWORK(R.string.miner_state_paused_network),
    WIFI(R.string.miner_state_paused_wifi),
    CHARGING(R.string.miner_state_paused_charging),
    FAILED_EXAM(R.string.miner_state_failed_exam),
    NEEDS_REVIEW(R.string.miner_state_needs_review),
    PLATFORM_PAUSED(R.string.miner_state_paused_platform),
    KEY_BAD(R.string.miner_state_key_bad),
    CONNECTING(R.string.miner_state_connecting);

    companion object {
        // T51 reports admission history explicitly. Passing still requires both existing gates.
        fun admission(status: String?, approved: Boolean, examPassed: Boolean): MinerState = when (status) {
            "waiting_exam" -> WAITING_EXAM
            "exam_running" -> EXAM
            "failed" -> FAILED_EXAM
            "needs_review" -> NEEDS_REVIEW
            "passed" -> if (approved && examPassed) ONLINE else PLATFORM_PAUSED
            else -> if (approved && examPassed) ONLINE else WAITING_EXAM
        }

        fun pause(online: Boolean, wifi: Boolean, charging: Boolean, battery: Int,
                  wifiOnly: Boolean, chargingOnly: Boolean, used: Long, limit: Long, minBattery: Int): MinerState? = when {
            used >= limit -> LIMIT
            !online -> NETWORK
            wifiOnly && !wifi -> WIFI
            chargingOnly && !charging -> CHARGING
            !charging && battery < minBattery -> BATTERY
            else -> null
        }
    }
}

internal fun minerNumberMessage(number: String): Int =
    if (number.isBlank()) R.string.miner_number_missing else R.string.miner_number

internal fun minerDrawerMessage(enabled: Boolean, state: MinerState): Int? = when {
    state == MinerState.KEY_BAD -> R.string.miner_drawer_attention
    !enabled -> null
    else -> when (state) {
        MinerState.ONLINE, MinerState.WORKING -> R.string.miner_drawer_running
        MinerState.CONNECTING, MinerState.WAITING_EXAM, MinerState.EXAM -> R.string.miner_drawer_preparing
        MinerState.FAILED_EXAM -> R.string.miner_drawer_attention
        else -> R.string.miner_drawer_paused
    }
}
