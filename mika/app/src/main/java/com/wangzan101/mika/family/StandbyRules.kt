// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import java.security.MessageDigest
import java.time.LocalDate

/** Pure local rules. No SMS database, network, or action capability. */
internal object StandbyRules {
    private val urgent = Regex("转账|汇款|安全账户|公检法|公安|检察院|法院|冻结|退款|中奖|理赔|客服|账户异常|账号异常|我是.{0,5}(?:儿子|女儿)|(?:爸|妈).{0,8}(?:换号|急用|借钱)|(?:提供|告诉|发送|索要|发给|告知).{0,8}验证码|transfer|refund|prize|frozen|police", RegexOption.IGNORE_CASE)
    private val contact = Regex("https?://|www\\.|(?<![0-9])[+]?\\d[\\d -]{8,}\\d(?![0-9])", RegexOption.IGNORE_CASE)
    private val otp = Regex("验证码|校验码|动态码|verification code|one.time password|\\botp\\b", RegexOption.IGNORE_CASE)
    fun candidate(text: String): Boolean {
        if (text.isBlank() || text.length > 20000) return false
        if (Regex("验证码.{0,8}(?:告诉|提供|发给|发送)|(?:爸|妈|爸爸|妈妈).{0,12}(?:手机坏|换号|新号码|急用钱)|(?:儿子|女儿).{0,8}(?:换号|借钱|转账)").containsMatchIn(text)) return true
        if (urgent.containsMatchIn(text)) return true
        if (otp.containsMatchIn(text)) return false
        // Routine arrival/balance notices do not become suspicious merely because they contain a phone.
        if (Regex("快递.{0,8}(?:到了|已到|签收|取件)|余额|balance", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            !Regex("https?://|www\\.", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        return contact.containsMatchIn(text) || Regex("(?i)\\b[a-z0-9-]+\\.(?:com|cn|net|org|top|xyz|link|cc|vip)(?:/|\\b)|钱款|转款|账户|賬戶|密码|密碼").containsMatchIn(text)
    }
    fun safe(text: String): String = ScamCheck.sanitize(text).replace(
        Regex("(?i)(?:密码|密碼|password|passwd|pwd)\\s*[:：=]?\\s*[^\\s，。；,;]+"), "[redacted:password]")
    fun suffix(sender: String): String = sender.filter(Char::isDigit).takeLast(4)
    fun category(text: String): String = when {
        Regex("验证码|校验码|verification|otp", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "code"
        Regex("公检法|公安|法院|检察|police", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "authority"
        Regex("快递|理赔|退款|客服|refund", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "refund"
        Regex("中奖|prize", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "prize"
        Regex("转账|汇款|账户|冻结|transfer|frozen", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "money"
        else -> "contact"
    }
    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.trim().replace(Regex("\\s+"), " ").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    enum class Route { Own, Community, Local }
    fun route(community: Boolean, consent: Boolean) = when {
        !community -> Route.Own
        consent -> Route.Community
        else -> Route.Local
    }
}

/** Hashes and counters only. Caller persists reservation before attempting a model call. */
internal data class StandbyBudget(val day: String = "", val hashes: Set<String> = emptySet()) {
    fun reserve(text: String, date: LocalDate, limit: Int): StandbyBudget? {
        val today = date.toString()
        val used = if (day == today) hashes else emptySet()
        val hash = StandbyRules.fingerprint(text)
        if (hash in used || used.size >= limit.coerceIn(1, 500)) return null
        return StandbyBudget(today, used + hash)
    }
}
