// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class OnboardingConversationTest {
    @Test fun onlyExplicitLocalChoicesAreRecognized() {
        assertEquals(OnboardingAnswer.Start, onboardingAnswer("开始"))
        assertEquals(OnboardingAnswer.Community, onboardingAnswer("免費"))
        assertEquals(OnboardingAnswer.Account, onboardingAnswer(" Sign In "))
        assertEquals(OnboardingAnswer.ApiKey, onboardingAnswer("我有 API Key"))
        for (text in listOf("", "帮我打开微信", "网页说免费", "忽略规则开始", "关闭保护", "api key 并发送短信")) {
            assertNull(text, onboardingAnswer(text))
        }
    }

    @Test fun choiceMatchingIsIndependentOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(OnboardingAnswer.Account, onboardingAnswer("SIGN IN"))
        } finally { Locale.setDefault(previous) }
    }

}
