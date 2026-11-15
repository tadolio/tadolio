// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.points

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Caller serializes access. Write failures preserve the previous in-memory snapshot. */
class PointsOutbox(read: () -> String, private val write: (String) -> Unit) {
    var entries: List<PointsPending> = Json.decodeFromString(read())
        private set
    fun replace(items: List<PointsPending>) {
        write(Json.encodeToString(items))
        entries = items
    }
    fun enqueue(report: PointsReport) {
        if (entries.none { it.report.taskId == report.taskId }) replace(entries + PointsPending(report))
    }
    fun settle(item: PointsPending, code: Int?, now: Long, retryAfterMs: Long = 0) {
        replace(entries.filterNot { it.report.taskId == item.report.taskId } +
            listOfNotNull(PointsRetry.after(item, code, now, retryAfterMs)))
    }
    fun clear() = replace(emptyList())
}
