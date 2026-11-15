// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorLookupTest {
    @Test fun providerErrorCopyMatchesBothLanguages() {
        val chinese = com.wangzan101.mika.i18n.ResFileTexts.chinese
        val english = com.wangzan101.mika.i18n.ResFileTexts.english
        assertEquals("没找到这个邀请码，核对一下再试。", chinese.string(R.string.operator_invite_not_found, emptyArray()))
        assertEquals("这个服务商人满了，换一个吧。", chinese.string(R.string.operator_full_error, emptyArray()))
        assertEquals("这个服务商暂时不能用，稍后再试或换一个。", chinese.string(R.string.operator_unavailable_error, emptyArray()))
        assertEquals("No invitation code found. Check it and try again.", english.string(R.string.operator_invite_not_found, emptyArray()))
        assertEquals("This provider is full. Try another one.", english.string(R.string.operator_full_error, emptyArray()))
        assertEquals("This provider is temporarily unavailable. Try again later or choose another.", english.string(R.string.operator_unavailable_error, emptyArray()))
    }
    @Test fun mapsProviderErrors() {
        assertEquals(R.string.operator_invite_not_found, operatorErrorText(Exception("community_invite_not_found")))
        assertEquals(R.string.operator_full_error, operatorErrorText(Exception("community_operator_full")))
        assertEquals(R.string.operator_unavailable_error, operatorErrorText(Exception("community_operator_unavailable")))
        assertEquals(R.string.community_unavailable, operatorErrorText(Exception("network")))
    }
    @Test fun switchesLookupLabels() {
        for (directory in listOf(true, false)) {
            assertEquals(R.string.operator_use_invite, operatorLookupText("ABC", directory))
        }
        assertEquals(R.string.operator_refresh, operatorLookupText("", true))
        assertEquals(R.string.operator_directory, operatorLookupText("", false))
        assertEquals(R.string.operator_refresh, operatorLookupText("  ", true))
    }
}
