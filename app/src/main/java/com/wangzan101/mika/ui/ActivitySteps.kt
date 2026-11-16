// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.record.RunEvent

/** The visible activity rows, shared by chat and points. Notes only change copy, never row count.
 * Reads with no known target are hidden unless failed; adjacent identical reads are merged.
 * Each other activity is one row, including activities in the same model turn.
 */
internal fun activityStepRows(
    activities: List<RunEvent.Activity>, toolLabel: (String) -> String,
    appLabel: (String) -> String, notes: Map<Int, String> = emptyMap(),
    includeNotices: Boolean = true,
): MutableList<StepRow> {
    val out = mutableListOf<StepRow>()
    val said = mutableSetOf<Int>()
    for (a in activities) {
        if (!includeNotices && a.kind == "notice") continue
        val where = a.target?.let { where(it, appLabel) }
        if (a.kind == "observe") {
            // 读成功了却不知道读的是哪里:不列;没读成的照样列出来
            if (where == null && !a.failed) continue
            val text = if (where == null) toolLabel(a.label)
                else a.title?.takeIf(String::isNotBlank)?.let { tr(R.string.step_viewed_part, where, it) } ?: tr(R.string.step_viewed, where)
            val subtitle = if (a.failed) tr(R.string.step_failed) else ""
            val last = out.lastOrNull()
            if (last?.title != text || last.subtitle != subtitle) out += StepRow(a.step, text, subtitle, StepState.Ok)
        } else {
            val reason = if (said.add(a.step)) notes[a.step].orEmpty() else ""
            val title = listOfNotNull(toolLabel(a.label), where).joinToString(" · ")
            out += StepRow(a.step, title, if (a.failed) tr(R.string.step_failed) else "", StepState.Ok, reason)
        }
    }
    return out
}

private fun where(target: String, appLabel: (String) -> String): String? = when {
    target.isBlank() -> null
    target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX)
    com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(target) -> appLabel(target)
    else -> target
}

// Persisted activity target prefix, not display copy.
private const val SITE_PREFIX = "网站 "
