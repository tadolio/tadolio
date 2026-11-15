// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ResFileTexts
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class ProviderContactCopyTest {
    @Test fun bindingDisclosureExplainsNumberContactBlockingAndUnbinding() {
        assertEquals("服务商能看到你的 Tadolio 号，并可以给你发消息；你可以随时拉黑或解绑。",
            ResFileTexts.chinese.string(R.string.community_binding_contact, emptyArray()))
        val english = ResFileTexts.english.string(R.string.community_binding_contact, emptyArray())
        for (term in listOf("Tadolio number", "send you messages", "block", "unbind")) assertTrue(english.contains(term))
        assertEquals(english, ResFileTexts("values-ja", Locale.JAPANESE)
            .string(R.string.community_binding_contact, emptyArray()))
    }

    @Test fun providerSourceIncludesPlatformAndFallsBackToEnglish() {
        assertEquals("来自你的服务商 Test Provider", ResFileTexts.chinese.string(
            R.string.number_provider_source, arrayOf("Test Provider")))
        assertEquals("From your provider Test Provider", ResFileTexts("values-ja", Locale.JAPANESE)
            .string(R.string.number_provider_source, arrayOf("Test Provider")))
    }

    @Test fun servedUsersEntryRequiresConfirmedCardAndUsesExistingConversation() {
        val source = File("src/main/java/com/wangzan101/mika/ui/NumberScreen.kt").readText()
        assertTrue(source.contains("if (providerCards.any { it.jsonObject.operatorFlag(\"confirmed\") && it.jsonObject.operatorText(\"kind\") == \"operator\" })"))
        assertTrue(source.contains("showingUsers = false; open(user.str(\"number\"))"))
        assertFalse(source.contains("R.string.number_claim"))
    }
}
