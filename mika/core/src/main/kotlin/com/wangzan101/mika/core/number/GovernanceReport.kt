// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.*
import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction

internal object GovernanceReport : AgentTool {
    override val verb = Verb.GovernanceReport
    // T31 reports remain read-only. Payment disputes separately require owner approval.
    override val risk = Risk.Read
    override val def = ToolDef("hivo_report", Prompts.tool("hivo_report"), mapOf(
        "kind" to str("Target kind.", listOf("post", "number", "node", "payment")),
        "id" to str("Target ID."),
        "reason" to str("Specific reason, up to 500 characters."),
    ), listOf("kind", "id", "reason"))
    override fun available(ctx: ToolContext) = ctx.config.governance != null
    override fun approvalRisk(args: JsonObject, ctx: ToolContext): ApprovalRisk? {
        if (args["kind"]?.jsonPrimitive?.content != "payment") return null
        return ApprovalRisk(ActionRisk.Irreversible, alwaysConfirm = true, ask = ConfirmationRequest(
            IrrevAction.submit, args["id"]?.jsonPrimitive?.content.orEmpty(),
            args["reason"]?.jsonPrimitive?.content.orEmpty(), null, "Tadolio", coinPayment = true))
    }
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.config.governance ?: return fail("unavailable", "Governance unavailable.")
        val kind = a.str("kind")!!
        if (kind == "payment" && ctx.config.runSpec?.readOnly == true) return fail("read_only", "Payment disputes need owner approval.")
        val id = a.str("id")!!
        val reason = a.str("reason")!!
        if (kind !in setOf("post", "number", "node", "payment")) throw ArgError("kind", "invalid kind")
        if (id.isBlank() || id.length > 128 || id.any { it.isISOControl() }) throw ArgError("id", "invalid ID")
        if (reason.isBlank() || reason.codePointCount(0, reason.length) > 500)
            throw ArgError("reason", "must be 1-500 characters")
        val case = port.report(kind, id, reason)
        return ok { put("id", case) }
    }
}
