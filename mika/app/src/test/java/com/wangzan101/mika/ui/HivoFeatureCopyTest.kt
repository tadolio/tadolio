// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class HivoFeatureCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun welcomeFilesFollowFeatureFlag() {
        assertEquals(BuildConfig.SANDBOX_ENABLED, WelcomeCopy.FEATURES.any { it.title == tr(R.string.welcome_files) })
        assertEquals("你好,我是 Tadolio", WelcomeCopy.TITLE)
    }

    @Test fun chineseBrandUsesUniversalName() {
        val oldBrand = Regex("H[i]vo|\u8702\u884c")
        for (texts in listOf(ResFileTexts.chinese, ResFileTexts.traditional)) {
            assertEquals("Tadolio", texts.strings.getValue("app_name"))
            texts.strings.forEach { (key, value) -> assertFalse("$key: $value", oldBrand.containsMatchIn(value)) }
        }
    }

    @Test fun newFeatureCopyUsesTadolioInEnglishChineseAndTraditional() {
        for (texts in listOf(ResFileTexts.english, ResFileTexts.chinese, ResFileTexts.traditional)) {
            texts.strings.filterKeys { key ->
                listOf("calls_", "teach_", "settings_self_", "tool_mika_").any(key::startsWith)
            }.forEach { (key, value) ->
                assertFalse("$key: $value", value.contains("Mika"))
            }
            assertEquals("Tadolio", texts.strings.getValue("app_name"))
        }
    }

    @Test fun disabledProxyHelpDoesNotAdvertiseComputing() {
        if (!BuildConfig.SANDBOX_ENABLED) {
            assertFalse(NetProxyCopy.HINT.contains("计算环境"))
            ResFileTexts.chinese.strings.filterKeys { it.endsWith("_no_sandbox") }.forEach { (key, value) ->
                assertFalse("$key: $value", Regex("沙箱|计算环境|文件和计算|文件类").containsMatchIn(value))
            }
        }
    }
}
