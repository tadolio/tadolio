// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import org.junit.Assert.*
import org.junit.Test
import com.wangzan101.mika.i18n.ResFileTexts

class DisplayRedactionsTest {
    @Test fun allKnownTokensTranslateOnlyAtDisplayBoundary() {
        val original = "[redacted:card] [redacted:otp] [redacted:id] [redacted:password] [redacted:mnemonic] [redacted:private_key]"
        val shown = DisplayRedactions.localize(original) { "[$it hidden]" }
        assertFalse(shown.contains("redacted:"))
        assertTrue(shown.contains("[private_key hidden]"))
        assertTrue(original.contains("[redacted:card]"))
        assertEquals("ordinary [redacted:unknown]", DisplayRedactions.localize("ordinary [redacted:unknown]") { error("unknown token") })
    }

    @Test fun primaryLocalesHaveReadableLabelsAndSafeExamples() {
        for (texts in listOf(ResFileTexts.english, ResFileTexts.chinese, ResFileTexts.traditional)) {
            for (type in listOf("card", "otp", "id", "password", "mnemonic", "private_key"))
                assertFalse(texts.strings.getValue("hidden_$type").contains("redacted:"))
            val estimate = texts.strings.getValue("elder_estimate")
            assertFalse(estimate.contains("agent", ignoreCase = true))
            val examples = listOf("follow", "group_summary", "jobs", "multi_step", "alarms", "ai_apps")
                .joinToString { texts.strings.getValue("chat_example_$it") }
            for (word in listOf("wangzan", "Wang Zan", "王赞", "王贊", "150", "VIP", "广告", "廣告"))
                assertFalse(word, examples.contains(word, ignoreCase = true))
        }
    }
}
