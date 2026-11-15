// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.community.CommunityCrypto
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Twenty independent base32 digits carry exactly 100 random bits. S never leaves the clients. */
internal object FamilyShortCode {
    private const val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    fun create(): String = group(buildString { repeat(20) { append(alphabet[random.nextInt(32)]) } })
    private fun group(value: String) = value.chunked(4).joinToString("-")
    fun normalize(value: String): String {
        val digits = value.trim().uppercase(java.util.Locale.ROOT).removePrefix("HIVO-")
            .replace("-", "").replace('O', '0').replace('I', '1').replace('L', '1')
        require(digits.length == 20 && digits.all { it in alphabet })
        return group(digits)
    }
    private fun secret(code: String) = normalize(code).replace("-", "")
    fun index(code: String) = FamilyCrypto.hash(secret(code))
    private fun cipher(mode: Int, code: String, nonce: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        val key = MessageDigest.getInstance("SHA-256").digest("hivo-invite-key-v1\n${secret(code)}".toByteArray(Charsets.UTF_8))
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        key.fill(0)
        updateAAD("hivo-invite-v1\n${index(code)}".toByteArray(Charsets.UTF_8))
    }
    fun seal(code: String, invitation: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        return CommunityCrypto.encode(nonce + cipher(Cipher.ENCRYPT_MODE, code, nonce).doFinal(invitation.toByteArray(Charsets.UTF_8)))
    }
    fun open(code: String, ciphertext: String): String {
        require(ciphertext.length <= 8192)
        val bytes = CommunityCrypto.decode(ciphertext)
        require(bytes.size >= 28)
        return String(cipher(Cipher.DECRYPT_MODE, code, bytes.copyOfRange(0, 12)).doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
