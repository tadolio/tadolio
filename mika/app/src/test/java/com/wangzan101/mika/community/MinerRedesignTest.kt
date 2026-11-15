// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MinerRedesignTest {
    @Test fun presetsCustomDecimalsAndDefaultRoundTrip() {
        assertEquals(listOf(500_000L, 2_000_000L, 5_000_000L), MinerLimits.presets)
        assertEquals("2000000", MinerPolicy.initialLimit(""))
        for (tokens in MinerLimits.presets + listOf(750_001L, 3_000_000L, 1L, MinerPolicy.MAX_DAILY_TOKENS)) {
            for (chinese in listOf(true, false)) {
                assertEquals(tokens, MinerLimits.parse(MinerLimits.display(tokens, chinese), chinese))
            }
        }
        assertEquals(3_005_000L, MinerLimits.parse("300.5", true))
        assertEquals(3_500_000L, MinerLimits.parse("3.5", false))
        assertFalse(3_005_000L in MinerLimits.presets)
        for (input in listOf("", "0", "-1", "NaN", "Infinity", "1e100", "0.00000001", "100000000000")) {
            assertNull(input, MinerLimits.parse(input, true))
            assertNull(input, MinerLimits.parse(input, false))
        }
    }

    @Test fun roughTasksUseFortyThousandAndTruncate() {
        assertEquals(40_000L, MinerLimits.TOKENS_PER_TASK)
        assertEquals(12L, MinerLimits.jobs(500_000))
        assertEquals(50L, MinerLimits.jobs(2_000_000))
        assertEquals(125L, MinerLimits.jobs(5_000_000))
        assertEquals(0L, MinerLimits.jobs(null))
        assertEquals(0L, MinerLimits.jobs(39_999))
    }

    @Test fun otherPlatformNeedsBothEndpointAndModelAndRestoresUnknownAddress() {
        val base = "https://custom.example.com/v1"
        assertNull(MinerPlatforms.find(base))
        assertFalse(MinerPolicy.canEnable("", "m", "test-only", 2_000_000, 20, "12345678"))
        assertFalse(MinerPolicy.canEnable(base, "", "test-only", 2_000_000, 20, "12345678"))
        assertTrue(MinerPolicy.canEnable(base, "m", "test-only", 2_000_000, 20, "12345678"))
        assertEquals(5, MinerPlatforms.all.size)
        val activity = File("src/main/java/com/wangzan101/mika/community/MinerActivity.kt").readText()
        assertTrue(activity.contains("MinerPlatforms.all.forEach"))
        assertTrue(activity.contains("R.string.miner_platform_other"))
        assertTrue(activity.contains("mutableStateOf(MinerPlatforms.find(base) == null)"))
        assertTrue(activity.contains("FlowRow(maxItemsInEachRow = 3"))
        assertFalse(activity.contains("horizontalScroll"))
    }

    @Test fun costStartsExpandedAndBalanceNoticeAlwaysPrecedesItsExpansion() {
        val activity = File("src/main/java/com/wangzan101/mika/community/MinerActivity.kt").readText()
        assertTrue(activity.contains("var showCost by remember { mutableStateOf(true) }"))
        assertTrue(activity.contains("showCost = !showCost"))
        assertTrue(activity.indexOf("R.string.miner_limit_note") < activity.indexOf("if (showCost)"))
        assertTrue(activity.contains("if (prices == null) stringResource(R.string.miner_cost_unknown) else stringResource(R.string.miner_cost_estimate"))
        assertEquals(0.3 to 1.2, MinerPricing.estimate(2_000_000, 0.15, 0.6))
        assertNull(MinerPricing.estimate(2_000_000, null, null))
        assertTrue(ResFileTexts.chinese.strings.getValue("miner_limit_note").contains("花的是你平台账户的余额，用多少扣多少。"))
        assertTrue(ResFileTexts.english.strings.getValue("miner_limit_note").contains("your own platform balance"))
    }

    @Test fun everyStateMapsToItsReviewedMessage() {
        val expected = mapOf(
            MinerState.WAITING_EXAM to R.string.miner_state_waiting_exam,
            MinerState.EXAM to R.string.miner_state_exam,
            MinerState.ONLINE to R.string.miner_state_online,
            MinerState.WORKING to R.string.miner_state_working,
            MinerState.LIMIT to R.string.miner_state_limit,
            MinerState.BATTERY to R.string.miner_state_paused_battery,
            MinerState.NETWORK to R.string.miner_state_paused_network,
            MinerState.WIFI to R.string.miner_state_paused_wifi,
            MinerState.CHARGING to R.string.miner_state_paused_charging,
            MinerState.FAILED_EXAM to R.string.miner_state_failed_exam,
            MinerState.NEEDS_REVIEW to R.string.miner_state_needs_review,
            MinerState.PLATFORM_PAUSED to R.string.miner_state_paused_platform,
            MinerState.KEY_BAD to R.string.miner_state_key_bad,
            MinerState.CONNECTING to R.string.miner_state_connecting
        )
        assertEquals(MinerState.entries.toSet(), expected.keys)
        expected.forEach { (state, message) ->
            assertEquals(message, state.message)
            assertTrue(ResFileTexts.chinese.string(message, emptyArray()).isNotBlank())
            assertTrue(ResFileTexts.english.string(message, emptyArray()).isNotBlank())
        }
    }

    @Test fun heartbeatAdmissionUsesT51HistoryAndPreservesQualificationGates() {
        assertEquals(MinerState.WAITING_EXAM, MinerState.admission("waiting_exam", false, false))
        assertEquals(MinerState.EXAM, MinerState.admission("exam_running", false, false))
        assertEquals(MinerState.FAILED_EXAM, MinerState.admission("failed", false, false))
        assertEquals(MinerState.NEEDS_REVIEW, MinerState.admission("needs_review", false, false))
        assertEquals(MinerState.ONLINE, MinerState.admission("passed", true, true))
        assertEquals(MinerState.PLATFORM_PAUSED, MinerState.admission("passed", false, true))
        assertEquals(MinerState.PLATFORM_PAUSED, MinerState.admission("passed", true, false))
        for (status in listOf("passed", null, "future_status")) {
            assertNotEquals(MinerState.ONLINE, MinerState.admission(status, false, true))
            assertNotEquals(MinerState.ONLINE, MinerState.admission(status, true, false))
        }
        // Older hubs omit examStatus. A false examPassed alone does not prove exam failure.
        assertEquals(MinerState.WAITING_EXAM, MinerState.admission(null, false, false))
        assertEquals(MinerState.ONLINE, MinerState.admission(null, true, true))
        val worker = File("src/main/java/com/wangzan101/mika/community/MinerWorker.kt").readText()
        assertTrue(worker.contains("result[\"examStatus\"]"))
        assertFalse(worker.contains("if(examining)"))
    }

    @Test fun everyLocalPauseHasMeasuredReasonAndCanResume() {
        fun reason(online: Boolean = true, wifi: Boolean = true, charging: Boolean = true,
                   battery: Int = 80, used: Long = 0) =
            MinerState.pause(online, wifi, charging, battery, true, true, used, 2_000_000, 20)
        assertNull(reason())
        assertEquals(MinerState.NETWORK, reason(online = false))
        assertEquals(MinerState.WIFI, reason(wifi = false))
        assertEquals(MinerState.CHARGING, reason(charging = false))
        assertEquals(MinerState.BATTERY, MinerState.pause(true, true, false, 10, false, false, 0, 2_000_000, 20))
        assertEquals(MinerState.LIMIT, reason(used = 2_000_000))
        assertEquals(MinerState.LIMIT, reason(used = 2_000_001))
    }

    @Test fun runningLimitUpdatesAcrossStoreInstancesWithoutLosingIdentityOrUsage() {
        val file = File.createTempFile("t50-limit", ".encrypted", File("build"))
        file.delete()
        val prefs = MemoryPrefs()
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray) = data
        }
        val ui = MinerStore(file, cipher, prefs)
        val worker = MinerStore(file, cipher, prefs)
        try {
            ui.save(buildJsonObject { put("limit", 2_000_000); put("nodeId", "test-node"); put("key", "test-only") })
            worker.enabled = true
            assertTrue(worker.reserve(400_000, 2_000_000))
            ui.changeLimit(500_000)
            assertTrue(worker.enabled)
            assertFalse(worker.reserve(100_001, 2_000_000))
            assertEquals(400_000L, worker.used())
            assertEquals("test-node", worker.read().getValue("nodeId").jsonPrimitive.content)
            assertEquals("test-only", worker.read().getValue("key").jsonPrimitive.content)
            assertEquals(500_000L, worker.read().getValue("limit").jsonPrimitive.long)
            ui.changeLimit(5_000_000)
            assertTrue(worker.reserve(100_001, 5_000_000))
            assertEquals(500_001L, ui.used())
        } finally { file.delete() }
    }

    @Test fun expiredOnlineStateIsNeverPresentedAsLiveAndKeyFailureSurvivesStop() {
        var at = 1_800_000_000_000L
        val file = File("build/t50-state-unused.encrypted")
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray) = data
        }
        val store = MinerStore(file, cipher, MemoryPrefs()) { at }
        store.phase = MinerState.ONLINE
        assertEquals(MinerState.ONLINE, store.state())
        at += 46_000
        assertEquals(MinerState.CONNECTING, store.state())
        store.pauseReason = MinerState.WIFI
        assertEquals(MinerState.WIFI, store.state())
        store.phase = MinerState.KEY_BAD
        store.enabled = false
        assertEquals(MinerState.KEY_BAD, store.state())
    }

    @Test fun reviewedCopyFallsBackToEnglishInOtherLanguages() {
        for (folder in listOf("values-ja", "values-ko", "values-ar", "values-fa", "values-es", "values-pt", "values-ru", "values-in", "values-b+zh+Hant")) {
            val texts = ResFileTexts(folder, Locale.ENGLISH)
            for (name in listOf("miner_state_online", "miner_limit_note", "miner_step_platform", "miner_exam", "miner_cost_unknown")) {
                val id = R.string::class.java.getField(name).getInt(null)
                assertEquals("$folder/$name", ResFileTexts.english.strings.getValue(name), texts.string(id, emptyArray()))
            }
        }
        assertFalse(ResFileTexts.chinese.strings.getValue("miner_exam").contains("排队"))
        assertTrue(ResFileTexts.chinese.strings.getValue("miner_exam").contains("可能要等一阵"))
    }
}
