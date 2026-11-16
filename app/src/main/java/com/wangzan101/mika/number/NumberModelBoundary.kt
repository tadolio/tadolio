// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.core.model.*

/** This boundary accepts only the number prompt, never a task context or private stores. */
internal object NumberModelBoundary {
    suspend fun complete(community: Boolean, system: String, prompt: String,
                         own: () -> ChatModel, remote: () -> ChatModel,
                         closeRemote: suspend (ChatModel) -> Unit): String {
        val model = if (community) remote() else own()
        try {
            val reply = model.complete(system,
                listOf(Message(Role.User, listOf(Part.Text(prompt)), Author.Mika)), emptyList())
            require(reply.parts.none { it is Part.ToolCall || it is Part.ToolResult })
            return reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        } finally {
            if (community) closeRemote(model)
        }
    }
}
