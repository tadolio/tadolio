// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.app.Notification
import android.app.Person
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Constructed notifications only; no actual SMS, device accounts or notification grant. */
class StandbyNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun samsungTextAndGoogleMessagingStyle() {
        val samsung = Notification.Builder(context, "test").setContentTitle("13800138000")
            .setContentText("退款请联系").setStyle(Notification.BigTextStyle().bigText("退款请联系 https://example.test")).build()
        assertEquals("退款请联系 https://example.test", StandbyNotifications.parse("sms", "sms", samsung).single().text)
        val person = Person.Builder().setName("13800138000").build()
        val style = Notification.MessagingStyle(Person.Builder().setName("Me").build())
            .addMessage("旧短信", 1, person).addMessage("中奖", 2, person)
        val google = Notification.Builder(context, "test").setStyle(style).build()
        assertEquals(StandbySms("13800138000", "中奖"), StandbyNotifications.parse("google", "google", google).single())
        assertTrue(StandbyNotifications.parse("wechat", "google", google).isEmpty())
        assertTrue(StandbyNotifications.parse("google", null, google).isEmpty())
        google.flags = google.flags or Notification.FLAG_GROUP_SUMMARY
        assertTrue(StandbyNotifications.parse("google", "google", google).isEmpty())
    }
}
