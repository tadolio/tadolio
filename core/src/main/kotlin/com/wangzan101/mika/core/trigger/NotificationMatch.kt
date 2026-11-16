// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.trigger

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

const val NOTIFICATION_MIN_INTERVAL_MS = 10 * 60_000L
const val NOTIFICATION_DAILY_LIMIT = 20

@Serializable
data class NotificationMatch(val packages: List<String>, val keywords: List<String> = emptyList()) {
    init { require(packages.isNotEmpty() && packages.all { it.isNotBlank() }) }
}

@Serializable
data class NotificationWake(val triggerId: String, val atMs: Long)

/** Only newly posted notifications from this connection are eligible. */
fun matchesNotification(match: NotificationMatch, packageName: String, title: String, text: String,
    postedAtMs: Long, connectedAtMs: Long): Boolean =
    postedAtMs >= connectedAtMs && packageName in match.packages &&
        (match.keywords.isEmpty() || match.keywords.any { title.contains(it, ignoreCase = true) || text.contains(it, ignoreCase = true) })

/** Reservations count even if dispatch fails. Dropped events are never replayed. */
fun notificationWakeAllowed(id: String, nowMs: Long, zone: ZoneId, wakes: List<NotificationWake>): Boolean {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return wakes.none { it.triggerId == id && nowMs - it.atMs < NOTIFICATION_MIN_INTERVAL_MS } &&
        wakes.count { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() == today } < NOTIFICATION_DAILY_LIMIT
}
