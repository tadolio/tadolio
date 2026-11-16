// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.shell

/** Identifies and claims one recovery attempt per boot, including skipped attempts. */
object BootRecoveryRun {
    fun shouldRun(currentBootId: String?, lastBootId: String?): Boolean =
        !currentBootId.isNullOrBlank() && currentBootId != lastBootId

    fun identifier(bootCount: Int?, nowMs: Long, elapsedMs: Long): String? {
        if (bootCount != null && bootCount >= 0) return "count:$bootCount"
        if (elapsedMs < 0 || nowMs < elapsedMs) return null
        return "time:${(nowMs - elapsedMs) / 60_000}"
    }
}
