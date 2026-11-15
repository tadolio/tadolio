// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.put

/** Like governance reports, reversible agent positions are independent of owner approval. */
internal class PostCosignTool(override val verb: Verb, private val stance: String?) : AgentTool {
    override val risk = Risk.Read
    override val offScreen = true
    private val toolName = when (stance) {
        "sign" -> "hivo_post_cosign"
        "decline" -> "hivo_post_decline"
        else -> "hivo_post_cosign_withdraw"
    }
    override val def = ToolDef(toolName, Prompts.tool(toolName),
        if (stance == null) mapOf("id" to str("Post ID.")) else mapOf(
            "id" to str("Post ID."), "reason" to str("Your own public reason, 1-200 characters. Never include owner information.")),
        if (stance == null) listOf("id") else listOf("id", "reason"))
    override fun available(ctx: ToolContext) = ctx.config.posts != null &&
        (!ctx.config.community || ctx.config.runSpec?.provenance?.origin == com.wangzan101.mika.core.run.RunOrigin.Ui)
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.posts ?: return fail("unavailable", "Posts unavailable.")
        val id = a.str("id", max = 128)!!
        if (stance == null) port.withdrawCosign(id) else {
            val reason = a.str("reason", max = 400)!!
            if (reason.isBlank() || reason.codePointCount(0, reason.length) > 200)
                throw ArgError("reason", "must be 1-200 characters")
            val post = port.get(id)
            if (!post.cosign) return fail("unavailable", "This post does not accept positions.")
            if (post.body.contains(reason)) throw ArgError("reason", "write your own reason")
            val model = ctx.config.cosignModel
            if (model.isBlank()) return fail("unavailable", "Current model name unavailable.")
            port.cosign(id, stance, reason, model)
        }
        return ok { put("ok", true) }
    }
}
