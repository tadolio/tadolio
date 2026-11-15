// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.core.model.*

/** Appended at the final model boundary, after all dynamically assembled sections. */
internal class ElderModel(private val delegate: ChatModel, private val suffix: String) : StreamingChatModel {
    override val capabilities get() = delegate.capabilities
    override val name get() = delegate.name
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        delegate.complete(append(system, suffix), messages, tools)
    override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply =
        if (delegate is StreamingChatModel) delegate.completeStreaming(append(system, suffix), messages, tools, onText)
        else delegate.complete(append(system, suffix), messages, tools)
    companion object {
        fun append(system: String, suffix: String) = if (suffix.isEmpty()) system else "$system\n\n$suffix"
    }
}
