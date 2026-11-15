// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.coins

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature check (RFC 8032), independent of the phone's security providers:
 * some phones (seen on OPPO, Android 16) ship without Ed25519, and the app's own process
 * failed even where a bare process found it. Only verifies public data, so it needs no
 * constant-time arithmetic.
 */
object Ed25519Verify {
    private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    fun verify(spki: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(spki.size == 44 && spki.copyOfRange(0, 12).contentEquals(SPKI_PREFIX)) { "not an Ed25519 key" }
        return verifyRaw(spki.copyOfRange(12, 44), message, signature)
    }

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P) % P
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)
    private val TWO_D = D.shiftLeft(1).mod(P)

    /** Extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BASE: Point = run {
        val y = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
        val x = checkNotNull(recoverX(y, false))
        Point(x, y, BigInteger.ONE, x * y % P)
    }

    private fun add(a: Point, b: Point): Point {
        val ea = (a.y - a.x) * (b.y - b.x) % P
        val eb = (a.y + a.x) * (b.y + b.x) % P
        val c = a.t * TWO_D % P * b.t % P
        val d = a.z.shiftLeft(1) * b.z % P
        val e = eb - ea; val f = d - c; val g = d + c; val h = eb + ea
        return Point(e * f % P, g * h % P, f * g % P, e * h % P)
    }

    private fun times(k: BigInteger, point: Point): Point {
        var result = IDENTITY
        for (i in k.bitLength() - 1 downTo 0) {
            result = add(result, result)
            if (k.testBit(i)) result = add(result, point)
        }
        return result
    }

    private fun same(a: Point, b: Point) =
        (a.x * b.z - b.x * a.z).mod(P).signum() == 0 && (a.y * b.z - b.y * a.z).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        if (y >= P) return null
        val y2 = y * y % P
        val x2 = (y2 - BigInteger.ONE) * (D * y2 + BigInteger.ONE).mod(P).modInverse(P) % P
        if (x2.mod(P).signum() == 0) return if (sign) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).mod(P).signum() != 0) x = x * SQRT_M1 % P
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != sign) x = P - x
        return x.mod(P)
    }

    private fun littleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

    private fun decode(bytes: ByteArray): Point? {
        val copy = bytes.copyOf()
        val sign = copy[31].toInt() and 0x80 != 0
        copy[31] = (copy[31].toInt() and 0x7f).toByte()
        val y = littleEndian(copy)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x * y % P)
    }

    internal fun verifyRaw(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decode(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decode(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val h = littleEndian(MessageDigest.getInstance("SHA-512").run {
            update(rBytes); update(publicKey); digest(message)
        }).mod(L)
        return same(times(s, BASE), add(r, times(h, a)))
    }
}
