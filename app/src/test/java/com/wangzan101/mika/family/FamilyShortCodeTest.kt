// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import org.junit.Assert.*
import org.junit.Test

class FamilyShortCodeTest {
    @Test fun twentyBase32DigitsAndCommonMistakes() {
        val codes = List(100) { FamilyShortCode.create() }
        assertEquals(100, codes.toSet().size)
        codes.forEach { assertTrue(it.matches(Regex("[0-9A-HJKMNP-TV-Z]{4}(?:-[0-9A-HJKMNP-TV-Z]{4}){4}"))) }
        val code = "01AB-01CD-01EF-01GH-01JK"
        assertEquals(code, FamilyShortCode.normalize("hivo-oiab-olcd-oief-oigh-oljk"))
        assertEquals(FamilyShortCode.index(code), FamilyShortCode.index(code.lowercase().replace('0', 'o').replace('1', 'i')))
    }
    @Test fun envelopesRejectTamperingWrongSecretAndTruncation() {
        val code = FamilyShortCode.create()
        val plain = "{\"name\":\"闺女\",\"secret\":\"test-private-secret\"}"
        val sealed = FamilyShortCode.seal(code, plain)
        assertEquals(plain, FamilyShortCode.open(code.lowercase(), sealed))
        assertFalse(sealed.contains("test-private-secret"))
        assertNotEquals(sealed, FamilyShortCode.seal(code, plain))
        val bytes = CommunityCrypto.decode(sealed)
        for (position in listOf(0, 12, bytes.lastIndex)) {
            val changed = bytes.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertTrue(runCatching { FamilyShortCode.open(code, CommunityCrypto.encode(changed)) }.isFailure)
        }
        assertTrue(runCatching { FamilyShortCode.open(FamilyShortCode.create(), sealed) }.isFailure)
        assertTrue(runCatching { FamilyShortCode.open(code, sealed.take(20)) }.isFailure)
    }
}
