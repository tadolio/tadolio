// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.model.*

/** Final boundary for auxiliary calls (intent, verifier, stop summary and outcome judge). */
class CommunityModel(private val inner: ChatModel, notice: () -> Unit = {}) : ChatModel by inner {
    private val messages = CommunityMessages(notice)
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        inner.complete(system, this.messages.outgoing(messages), tools)
}
