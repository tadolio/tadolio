// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.shell

import org.junit.Assert.*
import org.junit.Test

class BootRecoveryRunTest {
    @Test fun newBootRuns() {
        assertTrue(BootRecoveryRun.shouldRun("count:2", "count:1"))
        assertTrue(BootRecoveryRun.shouldRun("count:2", null))
    }

    @Test fun sameBootDoesNotRun() {
        assertFalse(BootRecoveryRun.shouldRun("count:2", "count:2"))
    }

    @Test fun unavailableIdentifierDoesNotRun() {
        assertFalse(BootRecoveryRun.shouldRun(null, null))
        assertFalse(BootRecoveryRun.shouldRun("", "count:2"))
        assertFalse(BootRecoveryRun.shouldRun(" ", null))
    }

    @Test fun bootCountHasPriority() {
        assertEquals("count:0", BootRecoveryRun.identifier(0, 120_000, 10_000))
    }

    @Test fun missingBootCountUsesBootMinute() {
        val first = BootRecoveryRun.identifier(null, 180_001, 60_000)
        val later = BootRecoveryRun.identifier(-1, 240_002, 120_001)
        assertEquals("time:2", first)
        assertFalse(BootRecoveryRun.shouldRun(later, first))
        assertTrue(BootRecoveryRun.shouldRun(BootRecoveryRun.identifier(null, 360_000, 60_000), first))
    }

    @Test fun invalidClocksHaveNoIdentifier() {
        assertNull(BootRecoveryRun.identifier(null, 1, 2))
        assertNull(BootRecoveryRun.identifier(null, 1, -1))
    }
}
