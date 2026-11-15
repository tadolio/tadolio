// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.*
import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction

/** Confirmation target of a public post; the app titles the card "publish publicly" instead of "send". */
const val PUBLIC_POST_TARGET = "hivo:public-post"

internal object PostPublish : AgentTool {
    override val verb = Verb.PostPublish
    override val risk = Risk.External
    override val approvalRisk = ApprovalRisk(ActionRisk.External)
    override val offScreen = true
    override val def = ToolDef("hivo_post_publish", Prompts.tool("hivo_post_publish"), mapOf(
        "title" to str("Public title, up to 80 characters."),
        "body" to str("Public body, up to 2000 characters."),
        "tags" to strArray("Your tags, each up to 24 characters.", 8),
        "coins" to int("Optional nonnegative coin price.", 0),
        "region" to str("Free-text region, up to 60 characters."),
        "url" to str("Your HTTPS URL."),
        "expires" to int("Expiry as Unix milliseconds, within 30 days."),
        "cosign" to bool("Invite agents and humans to state their position."),
        "shares" to buildJsonObject {
            put("type", "array"); put("maxItems", 4)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("number", str("Beneficiary's 8 digits."))
                    put("percent", int("Resale share of payment.", 1, 100))
                })
                put("required", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("percent"))))
                put("additionalProperties", false)
            })
        },
    ), listOf("title", "body"))
    override fun available(ctx: ToolContext) = ctx.config.posts != null && ctx.config.runSpec?.readOnly != true

    private fun draft(args: JsonObject): PostDraft {
        val a = Args(def, args)
        fun text(key: String, max: Int): String? = a.str(key)?.also {
            if (it.codePointCount(0, it.length) > max) throw ArgError(key, "too long")
        }
        fun integer(key: String): Long? {
            val value = args[key] ?: return null
            val primitive = value as? JsonPrimitive ?: throw ArgError(key, "must be an integer")
            return primitive.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
                ?: throw ArgError(key, "must be a nonnegative integer")
        }
        val tags = a.strList("tags", 0, 8, 48).orEmpty()
        if (tags.any { it.codePointCount(0, it.length) > 24 }) throw ArgError("tags", "tag too long")
        val url = a.str("url")
        if (url != null && !url.startsWith("https://")) throw ArgError("url", "must start with https://")
        val shares = args["shares"]?.let { value ->
            val array = value as? JsonArray ?: throw ArgError("shares", "must be an array")
            if (array.size > 4) throw ArgError("shares", "at most four beneficiaries")
            array.map { element ->
                val share = element as? JsonObject ?: throw ArgError("shares", "invalid share")
                if (share.keys != setOf("number", "percent")) throw ArgError("shares", "invalid fields")
                val number = (share["number"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val percent = (share["percent"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (number == null || !Regex("[0-9]{8}").matches(number) || percent == null || percent !in 1..100)
                    throw ArgError("shares", "invalid number or percent")
                PostShare(number, percent)
            }.also { if (it.sumOf { share -> share.percent } > 100) throw ArgError("shares", "total exceeds 100%") }
        }.orEmpty()
        return PostDraft(text("title", 80)!!, text("body", 2000)!!, tags,
            integer("coins"), text("region", 60), url, integer("expires"), shares, a.bool("cosign", false))
    }

    override fun approvalRisk(args: JsonObject): ApprovalRisk {
        val d = draft(args)
        // Every public field is in the readable content (and the price in amount), so changing any of them needs new consent.
        val content = listOfNotNull(d.title, d.body,
            d.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }, d.region, d.url,
            "cosign=${d.cosign}",
            d.shares.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.number}: ${it.percent}%" },
            d.expires?.let { java.time.Instant.ofEpochMilli(it).toString().take(10) }).joinToString(" / ")
        return ApprovalRisk(ActionRisk.External, ask = ConfirmationRequest(
            IrrevAction.send, PUBLIC_POST_TARGET, content, d.coins?.toString(), "Tadolio"), alwaysConfirm = true)
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.posts ?: return fail("unavailable", "Posts unavailable.")
        val id = port.publish(draft(a.o))
        return ok { put("id", id) }
    }
}

internal object PostWithdraw : AgentTool {
    override val verb = Verb.PostWithdraw
    override val risk = Risk.Write
    override val offScreen = true
    override val def = ToolDef("hivo_post_withdraw", Prompts.tool("hivo_post_withdraw"), mapOf(
        "id" to str("Your post ID."),
    ), listOf("id"))
    override fun available(ctx: ToolContext) = ctx.config.posts != null && ctx.config.runSpec?.readOnly != true
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.posts ?: return fail("unavailable", "Posts unavailable.")
        port.withdraw(a.str("id", max = 128)!!)
        return ok { put("withdrawn", true) }
    }
}

internal object PostSearch : AgentTool {
    override val verb = Verb.PostSearch
    override val risk = Risk.Read
    override val def = ToolDef("hivo_post_search", Prompts.tool("hivo_post_search"), mapOf(
        "q" to str("Substring to find."),
        "tag" to str("Publisher tag."),
        "number" to str("Publisher number."),
        "limit" to int("Maximum results.", 1, 100),
    ))
    override fun available(ctx: ToolContext) = ctx.config.posts != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.posts ?: return fail("unavailable", "Posts unavailable.")
        val posts = port.search(a.str("q", min = 0) ?: "", a.str("tag"), a.str("number"), a.int("limit", 1, 100) ?: 100)
        return ok { putJsonArray("posts") { posts.forEach { add(it.untrusted()) } } }
    }
}

internal object PostGet : AgentTool {
    override val verb = Verb.PostGet
    override val risk = Risk.Read
    override val def = ToolDef("hivo_post_get", Prompts.tool("hivo_post_get"), mapOf(
        "id" to str("Post ID."),
    ), listOf("id"))
    override fun available(ctx: ToolContext) = ctx.config.posts != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.posts ?: return fail("unavailable", "Posts unavailable.")
        val post = port.get(a.str("id", max = 128)!!)
        return ok { put("post", post.untrusted()) }
    }
}

internal object PostMine : AgentTool {
    override val verb = Verb.PostMine
    override val risk = Risk.Read
    override val def = ToolDef("hivo_post_mine", Prompts.tool("hivo_post_mine"))
    override fun available(ctx: ToolContext) = ctx.config.posts != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.posts ?: return fail("unavailable", "Posts unavailable.")
        val posts = port.mine()
        return ok { putJsonArray("posts") { posts.forEach { add(it.untrusted()) } } }
    }
}
