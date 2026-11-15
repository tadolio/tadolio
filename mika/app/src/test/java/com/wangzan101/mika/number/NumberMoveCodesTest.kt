// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NumberMoveCodesTest {
    @Test fun transferChecksLengthAsciiDigitsAndChecksumBeforeSending() {
        assertTrue(NumberMoveCodes.transferValid("1234 5670"))
        assertTrue(NumberMoveCodes.transferValid("1234-5670"))
        for (value in listOf("12345671", "1234567", "123456700", "１２３４５６７０", "1234X670"))
            assertFalse(NumberMoveCodes.transferValid(value))
    }

    @Test fun generatedRecoveryAcceptsGroupedInputAndRejectsEverySingleCharacterTypo() {
        val code = NumberMoveCodes.newRecovery()
        assertTrue(NumberMoveCodes.recoveryValid(code.chunked(4).joinToString("-")))
        assertTrue(NumberMoveCodes.recoveryValid(code.lowercase(Locale.ROOT)))
        for (i in code.indices) {
            val changed = code.toCharArray().also { it[i] = if (it[i] == 'A') 'B' else 'A' }
            assertFalse(NumberMoveCodes.recoveryValid(String(changed)))
        }
        assertFalse(NumberMoveCodes.recoveryValid(code.dropLast(1)))
        assertFalse(NumberMoveCodes.recoveryValid("0" + code.drop(1)))
        assertFalse(NumberMoveCodes.recoveryValid("I" + code.drop(1)))
    }
}
