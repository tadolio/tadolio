// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Local categories only. Never store the matched text, package title or a model supplied reason. */
enum class HybridReason {
    AppNotAllowed, WindowHidden, SensitiveContent, PasswordField, IrreversibleAction, RemoteTask, Image,
}

data class HybridUpgrade(val step: Int, val reason: HybridReason)

/**
 * One instance per run, shared by the main and every auxiliary caller.
 * The mutex linearizes upgrades with transport admission: after an upgrade no caller can enter
 * the community transport, including retries and summaries. There is deliberately no downgrade API.
 * This is a routing primitive; the host must also switch its screen/action/approval boundaries.
 */
class HybridCompute(ownAvailable: Boolean, remote: Boolean = false) {
    init { require(ownAvailable) { "hybrid_requires_own_model" } }
    private val mutex = Mutex()
    private val ownParts: MutableSet<Part> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    @Volatile var upgrade: HybridUpgrade? = if (remote) HybridUpgrade(1, HybridReason.RemoteTask) else null
        private set
    @Volatile var usedCommunity: Boolean = false
        private set

    suspend fun upgrade(step: Int, reason: HybridReason): HybridUpgrade = mutex.withLock {
        upgradeLocked(step, reason)
    }

    private fun upgradeLocked(step: Int, reason: HybridReason): HybridUpgrade {
        require(step >= 1)
        return upgrade ?: HybridUpgrade(step, reason).also { upgrade = it }
    }

    /** Admission and the complete transport call are one critical section, including cancellation. */
    internal suspend fun <T> route(
        step: Int, reason: HybridReason?, community: suspend () -> T, own: suspend () -> T,
    ): T = mutex.withLock {
        require(step >= 1)
        if (reason != null) upgradeLocked(step, reason)
        if (upgrade != null) own() else {
            // Conservative accounting: even an attempted request may already have reached a node.
            usedCommunity = true
            community()
        }
    }

    // Called only inside route's critical section. Shared across auxiliary and main model wrappers.
    internal fun rememberOwn(reply: ModelReply): ModelReply = reply.also { ownParts.addAll(it.parts) }
    internal fun portable(messages: List<Message>): List<Message> = messages.map { message ->
        message.copy(parts = message.parts.mapNotNull { part -> when {
            part in ownParts -> part
            part is Part.Thinking -> null
            part is Part.ToolCall -> part.copy(opaque = null)
            else -> part
        } })
    }
}

internal class HybridRequestChanged : Exception("hybrid_request_requires_own_rebuild")

/** Final outbound defense. Raw content stays local until routing has been decided. */
internal object HybridPreflight {
    private fun changed(text: String): Boolean {
        val safe = CommunitySensitive.payload(text)
        // payload canonicalizes JSON; whitespace/key ordering alone must not trigger an upgrade.
        return try { Json.parseToJsonElement(text) != Json.parseToJsonElement(safe) }
        catch (_: Exception) { text != safe }
    }

    fun reason(system: String, messages: List<Message>, tools: List<ToolSpec>): HybridReason? {
        if (changed(system)) return HybridReason.SensitiveContent
        if (tools.any { changed(it.name) || changed(it.description) || changed(it.parametersJsonSchema) })
            return HybridReason.SensitiveContent
        val adjacent = mutableListOf<String>()
        for (message in messages) for (part in message.parts) {
            val text = when (part) {
                is Part.Image -> return HybridReason.Image
                is Part.Text -> part.text
                is Part.ToolResult -> {
                    if (changed(part.callId)) return HybridReason.SensitiveContent
                    part.content
                }
                is Part.ToolCall -> {
                    if (changed(part.id) || changed(part.name)) return HybridReason.SensitiveContent
                    part.argsJson
                }
                is Part.Thinking -> part.text
            }
            if (changed(text)) return HybridReason.SensitiveContent
            adjacent += text
        }
        // Labels and values can arrive in separate text parts or consecutive tool messages.
        // Per-part sanitization alone would leave such a code unchanged in the node request.
        if (CommunitySensitive.sensitive(adjacent.joinToString("\n"))) return HybridReason.SensitiveContent
        return null
    }
}

/**
 * No error fallback to community. A failed own request leaves the run permanently upgraded.
 * Construct both models at run startup; never resolve the private model from mutable settings later.
 */
class HybridModel(
    private val communityModel: ChatModel,
    private val ownModel: ChatModel,
    val compute: HybridCompute,
    private val step: () -> Int,
) : ChatModel {
    /** Freeze an Own auxiliary model onto this run's existing community session and admission lock. */
    fun auxiliary(own: ChatModel): HybridModel {
        if (own is HybridModel) {
            require(own.compute === compute) { "hybrid_requires_shared_model_state" }
            return own
        }
        require(own !is CommunityModel) { "hybrid_requires_own_auxiliary" }
        return HybridModel(communityModel, own, compute, step)
    }

    private val communityMessages = CommunityMessages {}
    // Community starts with shared limits. Promotion restores the frozen Own model limits.
    override val capabilities get() = if (compute.upgrade != null) ownModel.capabilities else communityModel.capabilities.copy(
        vision = false,
        contextTokens = minOf(communityModel.capabilities.contextTokens, ownModel.capabilities.contextTokens),
        maxImages = 0,
        toolCalls = communityModel.capabilities.toolCalls && ownModel.capabilities.toolCalls,
    )
    override val name get() = if (compute.upgrade == null) communityModel.name else ownModel.name

    /** Inspect the full local history before callers strip foreign parts or compact old screens. */
    internal suspend fun preflight(system: String, messages: List<Message>, tools: List<ToolSpec>) {
        HybridPreflight.reason(system, messages, tools)?.let { compute.upgrade(step(), it) }
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        return completePrepared(system, messages, tools, preparedOwn = true)
    }

    /** Reject a community-prepared view if promotion won transport admission. */
    internal suspend fun completePrepared(system: String, messages: List<Message>, tools: List<ToolSpec>, preparedOwn: Boolean): ModelReply {
        val reason = HybridPreflight.reason(system, messages, tools)
        return compute.route(step(), reason,
            community = { communityModel.complete(system, communityMessages.outgoing(messages), tools) },
            own = {
                if (!preparedOwn) throw HybridRequestChanged()
                compute.rememberOwn(ownModel.complete(system, compute.portable(messages), tools))
            })
    }
}
