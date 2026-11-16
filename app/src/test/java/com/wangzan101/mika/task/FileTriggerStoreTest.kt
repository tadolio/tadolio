// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.task

import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSource
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTriggerStoreTest {
    @Test fun postRuleAndSharedWakeBudgetSurviveFileReopen() {
        val file = Files.createTempDirectory("post-trigger-test").resolve("triggers.json").toFile()
        val now = 1_800_000_000_000L
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val store = FileTriggerStore(file)
        store.upsert(TriggerDefinition("post", "thread", "Watch", 0,
            onPost = com.wangzan101.mika.core.trigger.PostMatch(listOf("bread"), 3, "west")))
        val window = store.claimPost("post", now, zone)!!
        store.mark(window.key, TriggerWindowState.Delivered)
        val reopened = FileTriggerStore(file)
        assertEquals(listOf("bread"), reopened.get("post")!!.onPost!!.keywords)
        assertEquals(3, reopened.get("post")!!.onPost!!.maxCoins)
        assertEquals(emptyList<Any>(), reopened.claimDue(now))
        org.junit.Assert.assertNull(reopened.claimPost("post", now + 1, zone))
        org.junit.Assert.assertNotNull(reopened.claimPost("post", now + 600_000, zone))
    }


    @Test fun preMergeNotificationFileRetainsRulesAndWakeBudget() {
        val file = Files.createTempDirectory("legacy-notification-test").resolve("triggers.json").toFile()
        val now = java.time.Instant.parse("2026-11-07T10:00:00Z").toEpochMilli()
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        file.writeText("""{"triggers":[{"id":"watch","threadId":"thread","goal":"Watch","nextAtMs":0,"onNotification":{"packages":["app.test"],"keywords":["parcel"]}}],"windows":[],"notificationWakes":[{"triggerId":"watch","atMs":$now}]}""")

        val restored = FileTriggerStore(file)
        val rule = restored.get("watch")!!
        assertEquals(listOf("app.test"), rule.onNotification!!.packages)
        assertEquals(listOf("parcel"), rule.onNotification!!.keywords)
        assertEquals(emptyList<Any>(), restored.claimDue(now))
        org.junit.Assert.assertNull(restored.claimNotification("watch", now + 1, zone))
        assertTrue(restored.edit(rule.copy(goal = "Updated", idleOnly = true)))

        val reopened = FileTriggerStore(file)
        assertEquals(rule.onNotification, reopened.get("watch")!!.onNotification)
        assertEquals("Updated", reopened.get("watch")!!.goal)
        assertEquals(true, reopened.get("watch")!!.idleOnly)
        assertEquals(1, Json.decodeFromString<TriggerSnapshot>(file.readText()).notificationWakes.size)
        org.junit.Assert.assertNotNull(reopened.claimNotification("watch", now + 600_000, zone))
        assertEquals(false, File(file.parentFile, file.name + ".corrupt").exists())
    }

    @Test fun repeatedAtomicReplacementRetainsMainAndConditionState() {
        val file = Files.createTempDirectory("replace-trigger-test").resolve("triggers.json").toFile()
        repeat(4) { revision ->
            FileTriggerStore(file).upsert(TriggerDefinition("price", "thread", "Watch $revision", 1_000,
                conditionGoal = "Price falls", baselineValue = "value-$revision",
                notifyWhen = ConditionNotify.EachTimeMet))
            val reopened = FileTriggerStore(file).get("price")!!
            assertEquals("Watch $revision", reopened.goal)
            assertEquals("value-$revision", reopened.baselineValue)
            assertEquals(ConditionNotify.EachTimeMet, reopened.notifyWhen)
            assertEquals(false, File(file.parentFile, file.name + ".tmp").exists())
            assertEquals(false, File(file.parentFile, file.name + ".conditions.tmp").exists())
        }
    }

    @Test fun notificationBudgetSurvivesReopenAndDeletion() {
        val file = Files.createTempDirectory("notification-trigger-test").resolve("triggers.json").toFile()
        val now = java.time.Instant.parse("2026-11-07T10:00:00Z").toEpochMilli()
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val match = com.wangzan101.mika.core.trigger.NotificationMatch(listOf("app.test"))
        for (i in 1..20) {
            val store = FileTriggerStore(file)
            store.upsert(TriggerDefinition("p$i", "t", "Watch", 0, onNotification = match))
            val window = store.claimNotification("p$i", now, zone)!!
            store.mark(window.key, TriggerWindowState.Delivered)
            store.remove("p$i")
        }
        val restored = FileTriggerStore(file)
        restored.upsert(TriggerDefinition("new", "t", "Watch", 0, onNotification = match))
        org.junit.Assert.assertNull(restored.claimNotification("new", now, zone))
        org.junit.Assert.assertNotNull(FileTriggerStore(file).claimNotification("new", now + 86_400_000L, zone))
    }

    @Test fun failedSidecarWriteKeepsPreviousScheduleInMemoryAndOnDisk() {
        assertInterruptedEditKeepsPreviousSchedule("triggers.json.conditions.tmp")
    }

    @Test fun failedPrimaryWriteKeepsPreviousScheduleInMemoryAndOnDisk() {
        assertInterruptedEditKeepsPreviousSchedule("triggers.json.tmp")
    }

    private fun assertInterruptedEditKeepsPreviousSchedule(blockedWrite: String) {
        val dir = Files.createTempDirectory("trigger-interrupted-edit").toFile()
        val file = File(dir, "triggers.json")
        val original = TriggerDefinition("multi", "thread", "remind", 1_000,
            calendarTime = "08:00", calendarZone = "UTC", calendarTimes = listOf("08:00", "12:30"))
        val edited = original.copy(nextAtMs = 2_000, calendarTimes = listOf("08:00", "20:00"))
        val store = FileTriggerStore(file)
        store.upsert(original)
        val blocker = File(dir, blockedWrite)
        assertTrue(blocker.mkdir())

        assertThrows(IOException::class.java) { store.edit(edited) }
        assertEquals(original, FileTriggerStore(file).get(original.id))
        assertEquals(original, store.get(original.id))

        assertTrue(blocker.delete())
        assertTrue(store.edit(edited))
        assertEquals(edited, FileTriggerStore(file).get(original.id))
    }

    @Test fun changingOnlyAnAdditionalClockCommitsWithIdenticalPrimaryContents() {
        val file = Files.createTempDirectory("trigger-times").resolve("triggers.json").toFile()
        val store = FileTriggerStore(file)
        val original = TriggerDefinition("multi", "thread", "remind", 1_000,
            calendarTime = "08:00", calendarZone = "UTC", calendarTimes = listOf("08:00", "12:30"))
        store.upsert(original)
        val primary = file.readText()
        val edited = original.copy(calendarTimes = listOf("08:00", "20:00"))

        assertTrue(store.edit(edited))
        assertEquals(primary, file.readText())
        assertEquals(edited, FileTriggerStore(file).get(original.id))
    }

    @Test fun multipleClocksSurviveClaimsAndReopenWithoutChangingLegacySchema() {
        val file = Files.createTempDirectory("trigger-times").resolve("triggers.json").toFile()
        val now = java.time.Instant.parse("2026-11-10T00:00:00Z").toEpochMilli()
        val store = FileTriggerStore(file)
        store.upsert(TriggerDefinition("multi", "thread", "remind", now,
            reminderText = "break", calendarTime = "08:00", calendarZone = "Asia/Shanghai",
            calendarTimes = listOf("08:00", "12:30", "20:00")))
        store.claimDue(now)
        val restored = FileTriggerStore(file)
        assertEquals(listOf("08:00", "12:30", "20:00"), restored.get("multi")!!.calendarClocks())
        assertEquals(java.time.Instant.parse("2026-11-10T04:30:00Z").toEpochMilli(), restored.get("multi")!!.nextAtMs)
        assertEquals(1, restored.windows().size)
        assertEquals(false, file.readText().contains("calendarTimes"))
        assertEquals("08:00", Json.decodeFromString<TriggerSnapshot>(file.readText()).triggers.single().calendarTime)
    }

    @Test fun invalidOrStaleClockExtensionFallsBackToTheLegacyClock() {
        val file = Files.createTempDirectory("trigger-times").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("multi", "thread", "check", 1000,
            calendarTime = "08:00", calendarZone = "UTC", calendarTimes = listOf("08:00", "20:00")))
        val sidecar = File(file.parentFile, file.name + ".conditions")
        sidecar.writeText("""{"states":{"multi":{"calendarTimes":["08:00","20:00"]}}}""")
        assertEquals(listOf("08:00", "20:00"), FileTriggerStore(file).get("multi")!!.calendarClocks())
        sidecar.writeText("""{"states":{"multi":{"calendarTimes":["08:00","bad"]}}}""")
        assertEquals(listOf("08:00"), FileTriggerStore(file).get("multi")!!.calendarClocks())
        assertEquals(false, File(file.parentFile, file.name + ".corrupt").exists())
        sidecar.writeText("""{"states":{"multi":{"calendarTimes":["09:00","20:00"]}}}""")
        assertEquals(listOf("08:00"), FileTriggerStore(file).get("multi")!!.calendarClocks())
    }

    @Test fun claimAndLedgerSurviveStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        val first = FileTriggerStore(file)
        first.upsert(TriggerDefinition("hourly", "thread", "check", 1_000, 1_000))
        first.claimDue(3_100)
        val restored = FileTriggerStore(file)
        assertEquals(4_000L, restored.get("hourly")!!.nextAtMs)
        assertEquals(2L, restored.windows().single().missedIntervals)
        assertEquals(TriggerWindowState.Claimed, restored.windows().single().state)
        assertEquals(emptyList<Any>(), restored.claimDue(3_100))
    }

    @Test fun reminderAndCalendarSettingsSurviveStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("daily", "thread", "remind", 1_000,
            reminderText = "该喝水了", calendarTime = "09:00", calendarZone = "Asia/Shanghai",
            calendarDays = listOf(1, 5)))
        val restored = FileTriggerStore(file).get("daily")!!
        assertEquals("该喝水了", restored.reminderText)
        assertEquals("09:00", restored.calendarTime)
        assertEquals(listOf(1, 5), restored.calendarDays)
    }

    @Test fun conditionStateSurvivesStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("price", "thread", "告诉我", 1_000, conditionGoal = "降价超过 50",
            notifyWhen = ConditionNotify.EachTimeMet, cooldownMs = 60_000, lastValue = "¥680",
            baselineValue = "¥700", baselineAtMs = 10, lastFiredAtMs = 10))
        val restored = FileTriggerStore(file).get("price")!!
        assertEquals(ConditionNotify.EachTimeMet, restored.notifyWhen)
        assertEquals(60_000L, restored.cooldownMs)
        assertEquals("¥680", restored.lastValue)
        assertEquals("¥700", restored.baselineValue)
        assertEquals(10L, restored.baselineAtMs)
        assertEquals(10L, restored.lastFiredAtMs)
    }

    // 来源属于扩展状态:存进 sidecar,不写进主文件,旧版严格解码仍能读整份计划。
    @Test fun sourceSurvivesStoreReopenWithoutEnteringTheMainFile() {
        val file = Files.createTempDirectory("trigger-source").resolve("triggers.json").toFile()
        val store = FileTriggerStore(file)
        store.upsert(TriggerDefinition("ext", "thread", "检查", 1_000, source = TriggerSource.External))
        store.upsert(TriggerDefinition("mine", "thread", "提醒", 2_000, reminderText = "喝水", source = TriggerSource.User))

        val restored = FileTriggerStore(file)
        assertEquals(TriggerSource.External, restored.get("ext")!!.source)
        assertEquals(TriggerSource.User, restored.get("mine")!!.source)

        assertEquals(false, file.readText().contains("source"))
        Json.decodeFromString<TriggerSnapshot>(file.readText())
    }

    // 升级前存下的计划文件没有新字段:照常读,按默认值算
    @Test fun planFileFromBeforeConditionStateStillLoads() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        file.writeText("""{"triggers":[{"id":"old","threadId":"thread","goal":"告诉我","nextAtMs":1000,"conditionGoal":"下雨","lastConditionMet":true}],"windows":[]}""")
        val restored = FileTriggerStore(file).get("old")!!
        assertEquals(true, restored.lastConditionMet)
        assertEquals(ConditionNotify.BecomesMet, restored.notifyWhen)
        assertEquals(null, restored.baselineValue)
        assertEquals(false, File(file.parentFile, file.name + ".corrupt").exists())
    }

    @Test fun oldReaderCanStillReadEveryPlanAfterConditionStateIsSaved() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        val store = FileTriggerStore(file)
        store.upsert(TriggerDefinition("fixed", "thread", "提醒", 1_000, reminderText = "喝水"))
        store.upsert(TriggerDefinition("price", "thread", "告诉我", 2_000, conditionGoal = "降价",
            notifyWhen = ConditionNotify.EachTimeMet, cooldownMs = 60_000, baselineValue = "¥700"))

        // 用旧版同样的严格解码读取主文件；扩展字段必须只在 sidecar 中，不能让旧版清空全部计划。
        val oldSchema = Json.decodeFromString<TriggerSnapshot>(file.readText())
        assertEquals(setOf("fixed", "price"), oldSchema.triggers.map { it.id }.toSet())
        assertEquals(false, file.readText().contains("baselineValue"))
        assertEquals(ConditionNotify.EachTimeMet, FileTriggerStore(file).get("price")!!.notifyWhen)
    }

    @Test fun damagedConditionSidecarDoesNotDamageMainPlans() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("fixed", "thread", "提醒", 1_000, reminderText = "喝水"))
        val sidecar = File(file.parentFile, file.name + ".conditions")
        sidecar.writeText("not json")

        val restored = FileTriggerStore(file)
        assertEquals("fixed", restored.all().single().id)
        assertEquals(true, File(file.parentFile, sidecar.name + ".corrupt").isFile)
        assertEquals(false, File(file.parentFile, file.name + ".corrupt").exists())
    }
}
