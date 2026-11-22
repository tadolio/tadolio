// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec

/** Standalone callers use the same switching policy as the loop, without owning its retry budget.
 * Task runs can supply the frozen transports directly to ModelCaller, which also handles retries,
 * per-model history and context compression. */
class FallbackChainModel(
    private val models: List<ChatModel>,
    private val providerIds: List<String?>,
    private val switched: (Int) -> Unit = {},
) : StreamingChatModel {
    init { require(models.isNotEmpty()) }
    private var index = 0
    override val name get() = models[index].name
    override val capabilities get() = models[index].capabilities

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        attempt { it.complete(system, messages, tools) }

    override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply =
        attempt { model ->
            if (model is StreamingChatModel) model.completeStreaming(system, messages, tools, onText)
            else model.complete(system, messages, tools)
        }

    private suspend fun attempt(call: suspend (ChatModel) -> ModelReply): ModelReply {
        while (true) {
            try { return call(models[index]) }
            catch (error: ModelError) {
                index = Failover.nextIndex(error, index, providerIds, models.size) ?: throw error
                switched(index)
            }
        }
    }
}
