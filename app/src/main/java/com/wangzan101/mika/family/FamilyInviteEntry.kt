// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

/** Only explicit invitation tokens are retained; unrelated clipboard content is discarded. */
internal object FamilyInviteEntry {
    private val token = Regex("(?<![A-Za-z0-9_-])HIVO-([A-Za-z0-9]{4}(?:-[A-Za-z0-9]{4}){4})(?![A-Za-z0-9_-])", RegexOption.IGNORE_CASE)
    private val link = Regex("(?:tadolio|hivo)://join\\?code=([A-Za-z0-9]{4}(?:-[A-Za-z0-9]{4}){4})(?![A-Za-z0-9_-])", RegexOption.IGNORE_CASE)
    fun acceptsLink(scheme: String?, host: String?) = scheme in setOf("tadolio", "hivo") && host == "join"
    fun extract(text: String): String? {
        if (text.length > 16384) return null
        val code = token.find(text)?.groupValues?.get(1) ?: link.find(text)?.groupValues?.get(1) ?: return null
        return runCatching { FamilyShortCode.normalize(code) }.getOrNull()
    }
    fun name(value: String) = value.trim().filter { !it.isISOControl() }.take(20).ifBlank { "家人" }
    fun share(code: String, name: String) = "${this.name(name)}邀请加入 Tadolio 家人：${link(code)}"
    fun link(code: String) = "tadolio://join?code=${FamilyShortCode.normalize(code)}"
}

internal class FamilyInviteSeen(private val read: (String) -> Boolean, private val save: (String) -> Boolean) {
    fun receive(text: String): String? {
        val code = FamilyInviteEntry.extract(text) ?: return null
        val digest = FamilyShortCode.index(code)
        if (read(digest) || !save(digest)) return null
        return code
    }
}

/** Description-only gate: no content reads for non-text or an unchanged clipboard timestamp. */
internal class FamilyClipboardGate(private val read: () -> Long?, private val save: (Long) -> Unit) {
    fun changed(text: Boolean, timestamp: Long): Boolean {
        if (!text || timestamp <= 0 || read() == timestamp) return false
        save(timestamp)
        return true
    }
}
