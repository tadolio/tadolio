// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import java.security.SecureRandom
import java.util.Locale

/** Input checks are for typing mistakes; only the hub can authorize a move. */
internal object NumberMoveCodes {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun normalize(value: String) = value.replace("-", "").replace(" ", "").uppercase(Locale.ROOT)

    fun transferValid(value: String): Boolean {
        val code = normalize(value)
        return code.length == 8 && code.all { it in '0'..'9' } &&
            code.last() - '0' == code.take(7).mapIndexed { i, c -> (c - '0') * (i + 1) }.sum() % 10
    }

    fun recoveryValid(value: String): Boolean {
        val code = normalize(value)
        return code.length == 24 && code.all { it in ALPHABET } &&
            ALPHABET.indexOf(code.last()) == code.take(23).sumOf { ALPHABET.indexOf(it) } % 32
    }

    fun newRecovery(): String {
        val random = SecureRandom()
        val prefix = (1..23).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return prefix + ALPHABET[prefix.sumOf { ALPHABET.indexOf(it) } % 32]
    }
}
