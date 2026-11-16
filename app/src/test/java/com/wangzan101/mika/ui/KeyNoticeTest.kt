// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyNoticeTest {
    @Test fun backupNoticeIsVisibleWithoutAddingAReportedStep() {
        val events = listOf(
            RunEvent.Activity(1, 1, "notice", "synthetic backup notice", source = "system"),
            RunEvent.Activity(2, 1, "act", "synthetic action"),
        )
        assertEquals(2, activityStepRows(events, { it }, { it }).size)
        assertEquals(1, activityStepRows(events, { it }, { it }, includeNotices = false).size)
    }
}
