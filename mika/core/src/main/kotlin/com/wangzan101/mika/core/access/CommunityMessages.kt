// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.model.*

/** Per-run outbound cache: sanitize each message once without rewriting append-only history. */
internal class CommunityMessages(private val userNotice: () -> Unit) {
    private val cache = java.util.IdentityHashMap<Message, Message>()
    fun clear() = cache.clear()
    fun outgoing(messages: List<Message>): List<Message> = messages.map { message ->
        cache.getOrPut(message) {
            var changed = false
            val parts = message.parts.mapNotNull { p ->
                when (p) {
                    is Part.Image -> p
                    is Part.Text -> p.copy(text = CommunitySensitive.payload(p.text)).also { changed = changed || it != p }
                    is Part.ToolResult -> p.copy(content = CommunitySensitive.payload(p.content)).also { changed = changed || it != p }
                    is Part.ToolCall -> p.copy(argsJson = CommunitySensitive.payload(p.argsJson), opaque = null)
                    is Part.Thinking -> p.copy(text = CommunitySensitive.redact(p.text), opaque = null)
                }
            }
            if (changed && (message.author == Author.User || message.parts.filterIsInstance<Part.ToolResult>().any { ("\"answer\"" in it.content || "\"userNote\"" in it.content) })) userNotice()
            message.copy(parts = parts)
        }
    }
}
