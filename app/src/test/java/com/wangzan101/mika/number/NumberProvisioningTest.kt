// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NumberProvisioningTest {
    @Test fun failureIsSilentAndBackoffSurvivesRestart() = runBlocking {
        var time = 1000L
        var next = 0L
        var failures = 0
        var attempts = 0
        var links = 0
        var offline = true
        fun worker() = NumberProvisioning({ next }, { failures }, { n, f -> next = n; failures = f },
            { attempts++; if (offline) error("offline") }, { links++ }, { time })
        worker().refresh()
        assertEquals(1, attempts)
        assertEquals(61000L, next)
        worker().refresh()
        assertEquals(1, attempts)
        time = next
        worker().refresh()
        assertEquals(2, attempts)
        assertEquals(time + 120000L, next)
        offline = false
        time = next
        worker().refresh()
        assertEquals(3, attempts)
        assertEquals(1, links)
        assertEquals(0, failures)
    }

    @Test fun existingNumberStillLinksAndFailedLinkRetries() = runBlocking {
        var time = 1L
        var next = 0L
        var failures = 0
        var links = 0
        var ensures = 0
        val worker = NumberProvisioning({ next }, { failures }, { n, f -> next = n; failures = f },
            { ensures++ }, { links++; if (links == 1) error("link failed") }, { time })
        worker.refresh()
        time = next
        worker.refresh()
        worker.refresh()
        assertEquals(2, ensures)
        assertEquals(2, links)
        assertEquals(0L, next)
    }

    @Test fun cancellationDoesNotCreateFailureBackoff() = runBlocking {
        var saved = false
        val worker = NumberProvisioning({ 0 }, { 0 }, { _, _ -> saved = true },
            { throw CancellationException() }, {})
        try { worker.refresh(); fail() } catch (_: CancellationException) { }
        assertFalse(saved)
    }
}
