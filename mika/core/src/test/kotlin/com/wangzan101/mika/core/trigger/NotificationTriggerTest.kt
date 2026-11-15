// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.trigger

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class NotificationTriggerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-07T10:00:00Z").toEpochMilli()
    private fun plan(id: String = "p") = TriggerDefinition(id, "t", "Watch", 0,
        onNotification = NotificationMatch(listOf("app.test"), listOf("paid", "ready")))

    @Test fun strictReadOnlyAssemblyOffersOnlyReadToolsAndRejectsWrites() = kotlinx.coroutines.runBlocking {
        val spec = com.wangzan101.mika.core.run.RunSpec(com.wangzan101.mika.core.run.RunId("n"), "Watch", "t",
            readOnly = true, allowReadOnlyNavigation = false)
        val tools = com.wangzan101.mika.core.assembly.toolDispatcher(com.wangzan101.mika.core.tool.FakeScreen(),
            web = com.wangzan101.mika.core.cap.web.FakeWebPort(), runSpec = spec)
        assertTrue(tools.index.names.isNotEmpty())
        for (name in tools.index.names) {
            assertEquals(name, com.wangzan101.mika.core.tool.Risk.Read,
                (tools.index[name] as com.wangzan101.mika.core.tool.AgentTool).risk)
        }
        assertNull(tools.index.nameOf(com.wangzan101.mika.core.tool.Verb.Navigate))
        assertNull(tools.index.nameOf(com.wangzan101.mika.core.tool.Verb.Tap))
        assertNull(tools.index.nameOf(com.wangzan101.mika.core.tool.Verb.SettingsSet))
        assertNull(tools.index.nameOf(com.wangzan101.mika.core.tool.Verb.ShareText))
    }

    @Test fun matchesPackageAndAnyKeywordInTitleOrBody() {
        val match = plan().onNotification!!
        assertTrue(matchesNotification(match, "app.test", "PAID", "", 100, 100))
        assertTrue(matchesNotification(match, "app.test", "", "ready now", 101, 100))
        assertFalse(matchesNotification(match, "other", "paid", "", 101, 100))
        assertFalse(matchesNotification(match, "app.test", "hello", "world", 101, 100))
    }
    @Test fun emptyKeywordsAcceptAnyNewNotificationButNotHistory() {
        val match = NotificationMatch(listOf("app.test"))
        assertTrue(matchesNotification(match, "app.test", "", "", 100, 100))
        assertFalse(matchesNotification(match, "app.test", "paid", "", 99, 100))
    }
    @Test fun cooldownIncludesExactBoundaryAndClockRollback() {
        val wakes = listOf(NotificationWake("p", now))
        assertFalse(notificationWakeAllowed("p", now + NOTIFICATION_MIN_INTERVAL_MS - 1, zone, wakes))
        assertTrue(notificationWakeAllowed("p", now + NOTIFICATION_MIN_INTERVAL_MS, zone, wakes))
        assertFalse(notificationWakeAllowed("p", now - 1, zone, wakes))
    }
    @Test fun globalDailyLimitAndLocalMidnightReset() {
        val wakes = (1..20).map { NotificationWake("p$it", now) }
        assertFalse(notificationWakeAllowed("new", now, zone, wakes))
        assertTrue(notificationWakeAllowed("new", now, zone, wakes.dropLast(1)))
        val midnight = Instant.parse("2026-11-07T16:00:00Z").toEpochMilli()
        assertTrue(notificationWakeAllowed("new", midnight, zone, wakes))
        assertFalse(notificationWakeAllowed("p", midnight, zone, listOf(NotificationWake("p", midnight - 1))))
    }
    @Test fun oldJsonStillDecodesAndNotificationsNeverEnterTimeScheduling() {
        val old = Json.decodeFromString<TriggerSnapshot>("""{"triggers":[{"id":"old","threadId":"t","goal":"g","nextAtMs":0}],"windows":[]}""")
        assertNull(old.triggers.single().onNotification)
        assertTrue(old.notificationWakes.isEmpty())
        val store = MemoryTriggerStore()
        store.upsert(plan())
        assertTrue(store.claimDue(now).isEmpty())
        var alarm: Long? = 1
        TriggerEngine(store, { alarm = it }) { _, _ -> fail("time dispatch") }.rearm(now)
        assertNull(alarm)
        assertTrue(upcoming(plan(), now, 3).isEmpty())
    }
    @Test fun reservationsSurviveRestartDeletionAndPause() {
        val store = MemoryTriggerStore()
        store.upsert(plan())
        val w = store.claimNotification("p", now, zone)!!
        store.mark(w.key, TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
        assertNotNull(store.get("p"))
        val restarted = MemoryTriggerStore(store.snapshot())
        assertNull(restarted.claimNotification("p", now + 1, zone))
        restarted.upsert(plan().copy(enabled = false))
        assertNull(restarted.claimNotification("p", now + NOTIFICATION_MIN_INTERVAL_MS, zone))
        assertTrue(TriggerEngine(restarted, {}) { _, _ -> }.setEnabled("p", true, now))
        restarted.remove("p")
        assertEquals(1, restarted.snapshot().notificationWakes.size)
        assertNull(restarted.get("p"))
    }
}
