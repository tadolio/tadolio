// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import org.junit.Assert.*
import org.junit.Test

class FamilyInviteEntryTest {
    private val code = "7K3M-9Q2X-T4WZ-8NPR-5VBD"
    @Test fun asksOnceAcrossEntriesAndRestartWithoutSavingClipboardContent() {
        val stored = mutableSetOf<String>()
        fun receiver() = FamilyInviteSeen({ it in stored }, { stored.add(it) })
        assertNull(receiver().receive("private clipboard text"))
        assertTrue(stored.isEmpty())
        assertEquals(code, receiver().receive("HIVO-$code"))
        assertNull(receiver().receive(FamilyInviteEntry.link(code).lowercase()))
        assertEquals(setOf(FamilyShortCode.index(code)), stored)
        val own = FamilyShortCode.create()
        stored.add(FamilyShortCode.index(own))
        assertNull(receiver().receive(FamilyInviteEntry.share(own, "闺女")))
    }
    @Test fun messageLinkAndShareResolveToSameInvitation() {
        val message = FamilyInviteEntry.share(code, "闺女")
        assertTrue(message.startsWith("闺女邀请"))
        assertTrue(message.length <= 80)
        assertTrue(FamilyInviteEntry.share(code, "女".repeat(100)).length <= 80)
        assertEquals(1, message.split("tadolio://join?code=").size - 1)
        assertEquals(code, FamilyInviteEntry.extract(message))
        assertEquals("tadolio://join?code=$code", FamilyInviteEntry.link(code))
        assertEquals(code, FamilyInviteEntry.extract(FamilyInviteEntry.link(code)))
        assertEquals(code, FamilyInviteEntry.extract("TADOLIO://join?code=${code.lowercase()}"))
        assertEquals(code, FamilyInviteEntry.extract("hivo://join?code=$code"))
        assertEquals(code, FamilyInviteEntry.extract("HIVO-$code"))
        assertEquals(code, FamilyInviteEntry.extract("hivo-${code.lowercase()}"))
        assertFalse(FamilyInviteEntry.link(code).contains('\n'))
        for (scheme in listOf("tadolio", "hivo")) {
            assertTrue(FamilyInviteEntry.acceptsLink(scheme, "join"))
            assertFalse(FamilyInviteEntry.acceptsLink(scheme, "other"))
        }
        assertFalse(FamilyInviteEntry.acceptsLink("https", "join"))
        assertFalse(FamilyInviteEntry.acceptsLink(null, null))
    }
    @Test fun ignoresUnrelatedClipboardAndOversizedInput() {
        for (value in listOf("", "我的验证码是123456", "https://example.com", "abcHIVO-$code", "HIVO-", "HIVO-abc", "HIVO-$code-1234", "x".repeat(16385))) {
            assertNull(FamilyInviteEntry.extract(value))
        }
        assertNull(FamilyInviteEntry.extract("HIVO-" + "x".repeat(4097)))
    }
    @Test fun nicknameIsBoundedAndDefaultsToFamily() {
        assertEquals("家人", FamilyInviteEntry.name("  "))
        assertEquals("闺女", FamilyInviteEntry.name(" 闺女\n"))
        assertEquals(20, FamilyInviteEntry.name("女".repeat(100)).length)
    }
    @Test fun unchangedOrNonTextDescriptionDoesNotReadContentAcrossRecreation() {
        var stored: Long? = null
        var reads = 0
        fun focus(text: Boolean, stamp: Long) {
            if (FamilyClipboardGate({ stored }, { stored = it }).changed(text, stamp)) reads++
        }
        focus(false, 100)
        assertEquals(0, reads)
        focus(true, 100)
        focus(true, 100)
        focus(false, 101)
        assertEquals(1, reads)
        focus(true, 102)
        focus(true, 0)
        assertEquals(2, reads)
    }
}
