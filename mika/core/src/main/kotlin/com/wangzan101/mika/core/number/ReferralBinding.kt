// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

/** Only a first-person referral statement from this conversation can authorize the setting. */
object ReferralBinding {
    fun explicitlyRequested(number: String, quote: String, utterances: List<String>): Boolean {
        if (!Regex("[0-9]{8}").matches(number) || quote !in utterances) return false
        val text = quote.trim().trimEnd('.', '。', '!', '！')
        return text.matches(Regex("我是\\s*$number\\s*推荐的")) ||
            text.matches(Regex("I (?:was referred|am recommended) by\\s+$number", RegexOption.IGNORE_CASE))
    }
}
