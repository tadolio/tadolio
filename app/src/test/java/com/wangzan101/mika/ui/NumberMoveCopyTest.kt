// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ResFileTexts
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class NumberMoveCopyTest {
    @Test fun warningsExplainCoinsSecrecyAndOldPhone() {
        val english=ResFileTexts.english.string(R.string.move_recovery_warning,emptyArray())
        for (term in listOf("number and coins","Never send","Tadolio will never ask","screenshot")) assertTrue(english.contains(term))
        assertEquals(english,ResFileTexts("values-ja",Locale.JAPANESE).string(R.string.move_recovery_warning,emptyArray()))
        assertTrue(ResFileTexts.chinese.string(R.string.move_recovery_warning,emptyArray()).contains("不要发给任何人"))
        assertTrue(ResFileTexts.chinese.string(R.string.move_old_warning,emptyArray()).contains("不能再用这个号码"))
    }
    @Test fun onboardingNumberPageAndSystemUnlockAreWiredToTheNormalEntryPoints() {
        val root=File("src/main/java/com/wangzan101/mika/ui")
        assertTrue(File(root,"WelcomeScreen.kt").readText().contains("R.string.t42_have_number"))
        assertTrue(File(root,"NumberScreen.kt").readText().contains("NumberMoveEntry"))
        val screen=File(root,"NumberMoveScreen.kt").readText()
        for (term in listOf("createConfirmDeviceCredentialIntent","Activity.RESULT_OK","moves.decide(row,true)","moves.decide(row,false)","moves.saved()","QrCodeImage","moves.restore")) assertTrue(screen.contains(term))
    }
}
