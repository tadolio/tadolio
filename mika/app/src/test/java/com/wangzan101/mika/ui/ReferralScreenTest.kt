// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ResFileTexts
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class ReferralScreenTest {
    private val ui = File("src/main/java/com/wangzan101/mika/ui")
    @Test fun welcomeCanContinueAndNumberPageOffersLateBinding() {
        val welcome = File(ui, "WelcomeScreen.kt").readText()
        assertTrue(welcome.contains("R.string.t60_start_free"))
        assertFalse(welcome.contains("R.string.referral_prompt"))
        assertTrue(File(ui, "DeferredSetupPrompts.kt").readText().contains("R.string.t60_referrer_title"))
        assertTrue(File(ui, "NumberScreen.kt").readText().contains("ReferralScreen(back"))
        val screen = File(ui, "ReferralScreen.kt").readText()
        assertTrue(screen.contains("\"canBind\""))
        assertTrue(screen.contains("if (canBind)"))
        assertTrue(screen.contains("R.string.referral_disclosure"))
        assertTrue(screen.contains("AlertDialog"))
        assertTrue(screen.contains("client.bindInviter(inviter)"))
    }
    @Test fun linkShowsConfirmationAndNeverReadsTheClipboard() {
        val screen = File(ui, "ReferralScreen.kt").readText()
        val activity = File(ui, "MainActivity.kt").readText()
        assertTrue(activity.contains("ReferralLink.number(input.dataString)"))
        assertTrue(activity.contains("ReferralScreen(initialInviter = number"))
        assertFalse(screen.contains("Clipboard"))
        assertFalse(screen.contains("LaunchedEffect(Unit) { work { client.bindInviter"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:host=\"invite\""))
    }
    @Test fun copyDisclosesNumberVisibilityAndUsesEnglishFallback() {
        val english = ResFileTexts.english
        val chinese = ResFileTexts.chinese
        assertEquals("邀请朋友用 Tadolio，你们各得金币。", chinese.string(R.string.referral_intro, emptyArray()))
        assertTrue(chinese.string(R.string.referral_disclosure, emptyArray()).contains("对方会看到你的 Tadolio 号"))
        assertTrue(english.string(R.string.referral_disclosure, emptyArray()).contains("will see your Tadolio number"))
        for (folder in listOf("values-ja", "values-ar", "values-es"))
            assertEquals(english.string(R.string.referral_intro, emptyArray()),
                ResFileTexts(folder, Locale.ENGLISH).string(R.string.referral_intro, emptyArray()))
        val screen = File(ui, "ReferralScreen.kt").readText()
        for (term in listOf("balance", "earned", "token", "leaderboard")) assertFalse(screen.contains(term))
        for (texts in listOf(english, chinese)) {
            val copy = texts.strings.filterKeys { it.startsWith("referral_") }.values.joinToString("\n")
            for (term in listOf("赚钱", "邀请越多得越多", "代币", "earn money", "token")) assertFalse(copy.contains(term))
        }
    }
}
