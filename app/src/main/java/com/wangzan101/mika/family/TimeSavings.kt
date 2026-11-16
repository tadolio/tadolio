// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import java.time.*
import java.time.temporal.TemporalAdjusters

/** Calendar week starts Monday. */
internal object TimeSavings {
    fun weekStart(now: Long, zone: ZoneId): Long = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
}
