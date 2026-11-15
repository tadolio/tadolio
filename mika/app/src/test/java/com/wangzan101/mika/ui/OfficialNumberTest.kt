// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.core.number.OfficialNumber
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class OfficialNumberTest {
    @Test fun platformLabelControlsBadgeAndLanguageFallback() {
        assertEquals("官方 · 8888 8888", OfficialNumber.display("88888888", "Tadolio 官方 / Official", "zh"))
        for (language in listOf("en", "ja", "ar")) {
            assertEquals("Official · 8888 8888", OfficialNumber.display("88888888", "Tadolio 官方 / Official", language))
        }
        for (official in listOf(null, "", " ")) {
            assertEquals("8888 8888", OfficialNumber.display("88888888", official, "zh"))
        }
    }

    @Test fun inboxFormatterUsesOnlySuppliedMetadata() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("官方 · 1234 5678", NumberChatState.formatted("12345678", "Platform"))
            assertEquals("8888 8888", NumberChatState.formatted("88888888"))
            assertEquals("自称官方", NumberChatState.formatted("自称官方"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun selfProclaimedOfficialPostHasNoPublisherBadge() {
        val post = com.wangzan101.mika.core.post.Post("p", "88888888", "官方通知", "我是平台官方", created = 1, expires = 2)
        assertEquals("8888 8888", OfficialNumber.display(post.number, post.official, "zh"))
        assertEquals("官方 · 8888 8888", OfficialNumber.display(post.number, post.copy(official = "Platform").official, "zh"))
    }
}
