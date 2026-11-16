// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.*

internal object NumberBindInviter : AgentTool {
    override val verb = Verb.NumberBindInviter
    override val risk = Risk.Write
    override val offScreen = true
    override val def = ToolDef("hivo_number_bind_inviter",
        Prompts.tool("hivo_number_bind_inviter"),
        mapOf("number" to str("Referrer's 8-digit Tadolio number."),
            "quote" to str("The user's exact first-person referral statement from this conversation.")),
        listOf("number", "quote"))
    override fun available(ctx: ToolContext) = ctx.config.bindInviter != null && ctx.config.runSpec?.readOnly != true
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val bind = ctx.config.bindInviter ?: return fail("unavailable", "Number unavailable.")
        val number = a.str("number")!!
        if (!ReferralBinding.explicitlyRequested(number, a.str("quote")!!, ctx.config.utterances?.all.orEmpty()))
            return fail("explicit_request_required", "The user must explicitly state who referred them in this conversation.")
        bind(number)
        return ok { put("bound", true); put("notice", "The other person will see your Tadolio number. 对方会看到你的 Tadolio 号。") }
    }
}

internal object NumberInbox : AgentTool {
    override val verb = Verb.NumberInbox
    override val risk = Risk.Read
    override val def = ToolDef("hivo_number_inbox", Prompts.tool("hivo_number_inbox"))
    override fun available(ctx: ToolContext) = ctx.config.number != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.number ?: return fail("unavailable", "Local mailbox unavailable.")
        return ok {
            putJsonArray("items") {
                port.inbox().forEach { item -> addJsonObject {
                    put("number", item.number)
                    put("official", item.official?.let(::JsonPrimitive) ?: JsonNull)
                    put("thread", item.thread)
                    put("subject", Untrusted.wrap(item.subject))
                    put("summary", Untrusted.wrap(item.summary))
                    put("state", item.state)
                    put("text", Untrusted.wrap(item.text))
                } }
            }
        }
    }
}

internal object NumberSend : AgentTool {
    override val verb = Verb.NumberSend
    override val risk = Risk.External
    override val approvalRisk = ApprovalRisk(ActionRisk.External)
    override val offScreen = true
    override val def = ToolDef("hivo_number_send", Prompts.tool("hivo_number_send"), mapOf(
        "number" to str("Recipient's 8 digits."),
        "kind" to str("Envelope kind.", listOf("ask", "inform", "propose", "reply")),
        "subject" to str("Subject, up to 60 characters."),
        "text" to str("Body, up to 2000 characters."),
        "thread" to str("Existing transaction ID, if replying."),
    ), listOf("number", "kind", "subject", "text"))
    override fun available(ctx: ToolContext) = ctx.config.number != null && ctx.config.runSpec?.readOnly != true
    private data class Fields(val number: String, val kind: String, val subject: String, val text: String, val thread: String?)
    private fun fields(args: JsonObject): Fields {
        val a = Args(def, args)
        val number = a.str("number")!!
        if (!Regex("[0-9]{8}").matches(number)) throw ArgError("number", "must be 8 digits")
        val kind = a.str("kind")!!
        if (kind !in setOf("ask", "inform", "propose", "reply")) throw ArgError("kind", "invalid kind")
        val subject = a.str("subject")!!
        val text = a.str("text")!!
        if (subject.isBlank() || subject.codePointCount(0, subject.length) > 60) throw ArgError("subject", "must be 1-60 characters")
        if (text.isBlank() || text.codePointCount(0, text.length) > 2000) throw ArgError("text", "must be 1-2000 characters")
        val thread = a.str("thread")
        if (thread != null && (thread.isBlank() || thread.length > 128 || thread.any { it.isISOControl() })) throw ArgError("thread", "invalid ID")
        return Fields(number, kind, subject, text, thread)
    }
    override fun approvalRisk(args: JsonObject): ApprovalRisk {
        val f = fields(args)
        // The owner sees exactly what goes out: recipient, subject and body. kind/thread only file the
        // message under a transaction and carry nothing the owner needs to approve.
        return ApprovalRisk(ActionRisk.External, ask = ConfirmationRequest(
            IrrevAction.send, f.number.chunked(4).joinToString(" "), "${f.subject}\n\n${f.text}",
            null, "Tadolio"), alwaysConfirm = true)
    }
    override fun approvalRisk(args: JsonObject, ctx: ToolContext): ApprovalRisk {
        val f = fields(args)
        return if (ctx.config.number?.contacted(f.number) == true) ApprovalRisk(ActionRisk.External)
            else approvalRisk(args)
    }
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.number ?: return fail("unavailable", "Local mailbox unavailable.")
        val f = fields(a.o)
        val community = port.community(f.number)
        port.send(f.number, f.kind, f.subject, f.text, f.thread)
        return ok {
            put("sent", true)
            if (community) put("notice", "This number is handled by community compute. Messages pass through a random miner.")
        }
    }
}
