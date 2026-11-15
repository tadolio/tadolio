// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.coins

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.*

internal object CoinsBalance : AgentTool {
    override val verb = Verb.CoinsBalance
    override val risk = Risk.Read
    override val def = ToolDef("hivo_coins_balance", Prompts.tool("hivo_coins_balance"))
    override fun available(ctx: ToolContext) = ctx.config.coins != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val balance = ctx.config.coins!!.balance()
        return ok {
            put("balance", balance.balance)
            put("entries", Json.encodeToJsonElement(balance.entries.take(10)))
        }
    }
}

internal object CoinsPay : AgentTool {
    override val verb = Verb.CoinsPay
    override val risk = Risk.External
    override val approvalRisk = ApprovalRisk(ActionRisk.Irreversible, payment = true, alwaysConfirm = true)
    override val offScreen = true
    override val def = ToolDef("hivo_coins_pay", Prompts.tool("hivo_coins_pay"), mapOf(
        "terms" to buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject {
                put("milestones", buildJsonObject {
                    put("type", "array"); put("minItems", 1); put("maxItems", 12)
                    put("items", buildJsonObject {
                        put("type", "object"); put("properties", buildJsonObject {
                            put("amount", int("Coins.", 1)); put("what", str("Delivery, up to 200 characters."))
                            put("due", int("Optional agreed date in milliseconds."))
                            put("atDue", str("Optional deadline action.", listOf("release", "refund")))
                        }); put("required", JsonArray(listOf("amount", "what").map(::JsonPrimitive)))
                        put("additionalProperties", false)
                    })
                })
            }); put("required", JsonArray(listOf(JsonPrimitive("milestones")))); put("additionalProperties", false)
        },
        "to" to str("Recipient's 8 digits."), "amount" to int("Coins to pay.", 1),
        "post" to str("Post ID, if paying for a post."), "note" to str("Optional note, up to 200 characters."),
    ), listOf("to", "amount"))
    override fun available(ctx: ToolContext) = ctx.config.coins != null && ctx.config.runSpec?.readOnly != true
    private fun payment(args: JsonObject): CoinPayment {
        val a = Args(def, args)
        val to = a.str("to")!!
        if (!Regex("[0-9]{8}").matches(to)) throw ArgError("to", "must be 8 digits")
        val amount = (args["amount"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (amount == null || amount !in 1..9007199254740991L) throw ArgError("amount", "must be a positive integer")
        val post = a.str("post", max = 36)
        val note = a.str("note", min = 0).orEmpty()
        if (note.codePointCount(0, note.length) > 200) throw ArgError("note", "too long")
        val terms = args["terms"]?.let { Json.decodeFromJsonElement<CoinTerms>(it) }
        terms?.let {
            require(it.milestones.size in 1..12)
            require(it.milestones.fold(java.math.BigInteger.ZERO) { sum, m ->
                require(m.amount > 0 && m.what.isNotBlank() && m.what.codePointCount(0, m.what.length) <= 200)
                require(m.due == null || m.due in 0..9007199254740991L)
                require(m.atDue == null || m.atDue in setOf("release", "refund"))
                sum + m.amount.toBigInteger()
            } == amount.toBigInteger())
        }
        return CoinPayment(to, amount, post, note, terms)
    }
    override fun approvalRisk(args: JsonObject, ctx: ToolContext): ApprovalRisk {
        val p = payment(args)
        val quote = checkNotNull(ctx.config.coins).preview(p)
        ctx.coinQuotes[p] = quote
        // The post's title instead of its id; the split list only when someone besides the payee gets a share.
        val shared = quote.splits.any { it.kind == "share" }
        val content = listOfNotNull(quote.title?.let { "「$it」" } ?: p.post, p.note.takeIf { it.isNotEmpty() },
            if (shared) quote.splits.joinToString(", ") { "${it.number} +${it.amount}" } else null).joinToString(" / ")
        return ApprovalRisk(ActionRisk.Irreversible, payment = true, ask = ConfirmationRequest(
            IrrevAction.pay, p.to, content, p.amount.toString(), "Tadolio", coinPayment = true, coinTerms = p.terms), alwaysConfirm = true)
    }
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val payment = payment(a.o)
        val quote = ctx.coinQuotes.remove(payment) ?: return fail("confirmation_required", "Confirm this payment first.")
        val receipt = ctx.config.coins!!.pay(payment, quote)
        return ok { put("receipt", Json.encodeToJsonElement(receipt.receipt).jsonObject.let {
            JsonObject(it + ("note" to JsonPrimitive(Untrusted.wrap(receipt.receipt.note))))
        }); put("signature", receipt.signature) }
    }
}

internal object CoinsReceipt : AgentTool {
    override val verb = Verb.CoinsReceipt
    override val risk = Risk.Read
    override val def = ToolDef("hivo_coins_receipt", Prompts.tool("hivo_coins_receipt"), mapOf("id" to str("Receipt ID.")), listOf("id"))
    override fun available(ctx: ToolContext) = ctx.config.coins != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val receipt = ctx.config.coins!!.receipt(a.str("id", max = 36)!!)
        return ok { put("receipt", Untrusted.wrap(Json.encodeToString(SignedCoinReceipt.serializer(), receipt))); put("valid", true) }
    }
}

/** Every action consumes one owner confirmation bound to its stage. */
internal class CoinsSettlement(private val action: String) : AgentTool {
    private val toolName = "hivo_coins_$action"
    override val verb = when (action) {
        "release" -> Verb.CoinsRelease
        "refund" -> Verb.CoinsRefund
        "accept" -> Verb.CoinsAccept
        "decline" -> Verb.CoinsDecline
        else -> Verb.CoinsCancel
    }
    override val risk = Risk.External
    override val approvalRisk = ApprovalRisk(ActionRisk.Irreversible, payment = true, alwaysConfirm = true)
    override val offScreen = true
    override val def = ToolDef(toolName, Prompts.tool(toolName), mapOf(
        "receipt" to str("Escrow receipt ID."), "milestone" to int("Stage index, starting at 0.", 0, 11)),
        if (action == "release") listOf("receipt", "milestone") else listOf("receipt"))
    override fun available(ctx: ToolContext) = ctx.config.coins != null && ctx.config.runSpec?.readOnly != true
    private fun stage(args: JsonObject): Int? = args["milestone"]?.jsonPrimitive?.int.also {
        require(it == null || action in setOf("release", "refund") && it in 0..11)
        require(action != "release" || it != null)
    }
    private fun key(id: String, stage: Int?) = "$id/${stage ?: "all"}"
    override fun approvalRisk(args: JsonObject, ctx: ToolContext): ApprovalRisk {
        val id = Args(def, args).str("receipt", max = 36)!!
        val stage = stage(args)
        val receipt = checkNotNull(ctx.config.coins).previewReceipt(id).receipt
        val terms = receipt.terms ?: receipt.takeIf { it.hold == true }?.let {
            CoinTerms(listOf(CoinMilestone(it.amount, it.note, it.releaseAt, "release")))
        } ?: error("escrow_receipt_required")
        val milestone = stage?.let { terms.milestones[it] }
        ctx.coinSettlements[toolName to key(id, stage)] = receipt
        return ApprovalRisk(ActionRisk.Irreversible, payment = true, alwaysConfirm = true,
            // The card names the other party: the buyer releases or cancels toward the seller,
            // the seller accepts, declines or refunds toward the buyer.
            ask = ConfirmationRequest(IrrevAction.pay, if (action == "release" || action == "cancel") receipt.to else receipt.from,
                milestone?.what ?: receipt.note.ifBlank { receipt.post ?: id.take(8) },
                (milestone?.amount ?: receipt.amount).toString(), "Tadolio", coinPayment = true,
                coinSettlement = action, coinTerms = if (stage == null) terms else null,
                coinMilestone = stage))
    }
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val id = a.str("receipt", max = 36)!!
        val stage = stage(a.o)
        ctx.coinSettlements.remove(toolName to key(id, stage)) ?: return fail("confirmation_required", "Confirm this action first.")
        val port = checkNotNull(ctx.config.coins)
        val result = when (action) {
            "release" -> port.release(id, checkNotNull(stage))
            "refund" -> port.refund(id, stage)
            "accept" -> port.accept(id)
            "decline" -> port.decline(id)
            else -> port.cancel(id)
        }
        return ok { put("receipt", Untrusted.wrap(Json.encodeToString(SignedCoinReceipt.serializer(), result))) }
    }
}
