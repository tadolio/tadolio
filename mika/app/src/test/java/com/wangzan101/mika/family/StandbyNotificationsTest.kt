// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import org.junit.Assert.*
import org.junit.Test

class StandbyNotificationsTest {
    @Test fun samsungBigTextTakesPrecedenceWithoutReadingInbox() {
        assertEquals(listOf(StandbySms("13800138000", "full refund message")),
            StandbyNotifications.fields("samsung", "samsung", false, "13800138000", "truncated", "full refund message", null))
    }
    @Test fun googleMessagingStyleUsesLatestMessageAndItsSender() {
        val history = listOf(StandbySms("old sender", "old text"), StandbySms("new sender", "new text"))
        assertEquals(listOf(history.last()), StandbyNotifications.fields("google", "google", false, "App title", "summary", null, history))
        assertTrue(StandbyNotifications.fields("google", "google", false, null, "summary", null, emptyList()).isEmpty())
    }
    @Test fun unrelatedAppsUnknownDefaultAndGroupSummariesNeverEnter() {
        for ((pkg, default, summary) in listOf(Triple("wechat","sms",false), Triple("sms",null,false), Triple("sms","sms",true))) {
            assertTrue(StandbyNotifications.fields(pkg, default, summary, "sender", "refund", "refund", listOf(StandbySms("sender","refund"))).isEmpty())
        }
        assertTrue(StandbyNotifications.fields("sms","sms",false,null,"x".repeat(20001),null,null).isEmpty())
    }
}
