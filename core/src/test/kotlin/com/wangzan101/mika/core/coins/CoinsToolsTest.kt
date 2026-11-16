// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.coins

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.tool.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CoinsToolsTest {
    private val args = buildJsonObject { put("to", "22222222"); put("amount", 80); put("post", "p"); put("note", "stroller") }
    private val splits = listOf(CoinSplit("33333333", 8, "share"), CoinSplit("44444444", 7, "share"), CoinSplit("22222222", 65, "receive"))
    private inner class Port : CoinsPort {
        var payments = 0
        var releases = 0
        var refunds = 0
        var lastPayment: CoinPayment? = null
        override fun preview(payment: CoinPayment) = CoinQuote(splits, "quote")
        override suspend fun balance() = CoinBalance(100, emptyList())
        override suspend fun pay(payment: CoinPayment, quote: CoinQuote): SignedCoinReceipt {
            payments++
            lastPayment = payment
            return SignedCoinReceipt(CoinReceipt("id", "11111111", payment.to, payment.amount, payment.post, quote.splits, payment.note, 0), "sig")
        }
        override fun previewReceipt(id: String) = SignedCoinReceipt(CoinReceipt(id, "11111111", "22222222", 30,
            null, listOf(CoinSplit("22222222", 30, "receive")), "seat", 1, hold = true, status = "held", releaseAt = 10), "sig")
        override suspend fun release(receipt: String, milestone: Int) = previewReceipt(receipt).also { releases++ }
        override suspend fun refund(receipt: String, milestone: Int?) = previewReceipt(receipt).also { refunds++ }
        override suspend fun accept(receipt: String) = previewReceipt(receipt)
        override suspend fun decline(receipt: String) = previewReceipt(receipt)
        override suspend fun receipt(id: String) = previewReceipt(id)
    }
    @Test fun everyPaymentRequiresExactOwnerConsentEvenAutonomous() = runTest {
        val port = Port()
        val tools = toolDispatcher(FakeScreen(), coins = port)
        val gate = ConfirmationGate()
        val judge = ConfirmDecision(tools, gate, ApprovalSettings { true }, autonomous = true)
        assertTrue(judge.judge("hivo_coins_pay", args, Origin.Model(1)) is Decision.Required)
        val request = tools.approvalRisk("hivo_coins_pay", args)!!.ask!!
        assertEquals(IrrevAction.pay, request.action)
        assertEquals("22222222", request.target)
        assertEquals("80", request.amount)
        listOf("p", "33333333 +8", "44444444 +7", "22222222 +65").forEach { assertTrue(request.content.contains(it)) }
        for ((key, value) in listOf("amount" to JsonPrimitive(81), "to" to JsonPrimitive("55555555"))) {
            gate.declare(request, request.app); gate.decide(true, 1)
            assertTrue(judge.judge("hivo_coins_pay", JsonObject(args + (key to value)), Origin.Model(1)) is Decision.Mismatch)
        }
        gate.declare(request, request.app); gate.decide(true, 1)
        assertEquals(Decision.Clear, judge.judge("hivo_coins_pay", args, Origin.Model(1)))
        CoinsPay.execute(Args(CoinsPay.def, args), tools.ctx)
        assertEquals(1, port.payments)
        assertTrue(judge.judge("hivo_coins_pay", args, Origin.Model(1)) is Decision.Required)
    }
    @Test fun unattendedDeniedReadOnlyHiddenCommunityPhoneOnly() = runTest {
        val tools = toolDispatcher(FakeScreen(), coins = Port(), runSpec = RunSpec(RunId("c"), "pay", "c", computeMode = ComputeMode.Community))
        assertTrue(tools.definitions().any { it.name == "hivo_coins_balance" })
        val gate = ConfirmationGate(phoneOnly = true)
        val judge = ConfirmDecision(tools, gate, autonomous = true)
        val request = tools.approvalRisk("hivo_coins_pay", args)!!.ask!!
        gate.declare(request, request.app); gate.decide(ConfirmChoice.Once, 1, onPhone = false)
        assertTrue(judge.judge("hivo_coins_pay", args, Origin.Model(1)) is Decision.Required)
        gate.decide(ConfirmChoice.Once, 1, onPhone = true)
        assertEquals(Decision.Clear, judge.judge("hivo_coins_pay", args, Origin.Model(1)))
        val unattended = ConfirmDecision(tools, gate, autonomous = true, provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        assertTrue(unattended.judge("hivo_coins_pay", args, Origin.Model(1)) is Decision.Denied)
        val readOnly = toolDispatcher(FakeScreen(), coins = Port(), runSpec = RunSpec(RunId("r"), "read", "c", readOnly = true))
        assertFalse(readOnly.definitions().any { it.name == "hivo_coins_pay" })
        assertTrue(readOnly.definitions().any { it.name == "hivo_coins_receipt" })
    }
    @Test fun exportIntentAndVoucherPersistBeforeNetworkAndRetrySameVoucher() = runTest {
        var disk: String? = null
        var deposits = 0
        val transfer = CoinTransfer({ disk }, { disk = it })
        var voucher: JsonObject? = null
        val export: suspend (JsonObject) -> JsonObject = { request ->
            assertEquals(request.toString(), disk)
            buildJsonObject { put("voucher", request); put("signature", "sig") }.also { voucher = it }
        }
        val deposit: suspend (JsonObject) -> Unit = {
            assertEquals(voucher.toString(), disk); assertEquals(voucher, it)
            if (deposits++ == 0) error("lost response")
        }
        try { transfer.poll("11111111", {100}, export, deposit); fail() } catch (_: IllegalStateException) { }
        CoinTransfer({ disk }, { disk = it }).poll("11111111", {error("must not query")}, {error("must not re-export")}, deposit)
        assertNull(disk); assertEquals(2, deposits)
    }
    @Test fun incomingPaymentsAreMergedAndDeduplicated() {
        var disk: Set<String>? = null
        val incoming = CoinIncoming({ disk }, { disk = it })
        assertTrue(incoming.collect(emptyList()).isEmpty())
        val entries = listOf(CoinEntry("1", "11111111", 65, "receive", at = 1), CoinEntry("2", "11111111", 7, "share", at = 1), CoinEntry("3", "11111111", -1, "pay", at = 1))
        assertEquals(72L, incoming.collect(entries).sumOf { it.delta })
        assertTrue(incoming.collect(entries).isEmpty())
    }
    @Test fun holdBindsConsentAndReachesPaymentPort() = runTest {
        val port = Port()
        val tools = toolDispatcher(FakeScreen(), coins = port)
        val terms = CoinTerms(listOf(CoinMilestone(80, "Delivery")))
        val held = JsonObject(args + ("terms" to Json.encodeToJsonElement(terms)))
        val request = tools.approvalRisk("hivo_coins_pay", held)!!.ask!!
        assertEquals(terms, request.coinTerms)
        val gate = ConfirmationGate()
        val judge = ConfirmDecision(tools, gate, autonomous = true)
        gate.declare(request, request.app); gate.decide(true, 1)
        assertTrue(judge.judge("hivo_coins_pay", args, Origin.Model(1)) is Decision.Mismatch)
        gate.declare(request, request.app); gate.decide(true, 1)
        assertEquals(Decision.Clear, judge.judge("hivo_coins_pay", held, Origin.Model(1)))
        CoinsPay.execute(Args(CoinsPay.def, held), tools.ctx)
        assertEquals(terms, port.lastPayment!!.terms)
    }
    @Test fun settlementCardNamesTheOtherParty() = runTest {
        val tools = toolDispatcher(FakeScreen(), coins = Port())
        val args = buildJsonObject { put("receipt", "00000000-0000-0000-0000-000000000001") }
        // The seller accepts or declines toward the buyer; the buyer cancels toward the seller.
        for ((name, target) in listOf("hivo_coins_accept" to "11111111", "hivo_coins_decline" to "11111111",
            "hivo_coins_cancel" to "22222222")) {
            assertEquals(name, target, tools.approvalRisk(name, args)!!.ask!!.target)
        }
    }
    @Test fun releaseAndRefundEachNeedOneOwnerApprovalAndDenyUnattended() = runTest {
        val port = Port()
        val tools = toolDispatcher(FakeScreen(), coins = port)
        val id = "00000000-0000-0000-0000-000000000001"
        val args = buildJsonObject { put("receipt", id); put("milestone", 0) }
        for ((name, refund) in listOf("hivo_coins_release" to false, "hivo_coins_refund" to true)) {
            val gate = ConfirmationGate()
            val judge = ConfirmDecision(tools, gate, autonomous = true)
            assertTrue(judge.judge(name, args, Origin.Model(1)) is Decision.Required)
            val request = tools.approvalRisk(name, args)!!.ask!!
            assertEquals(if (refund) "11111111" else "22222222", request.target)
            gate.declare(request, request.app); gate.decide(true, 1)
            assertEquals(Decision.Clear, judge.judge(name, args, Origin.Model(1)))
            val tool = CoinsSettlement(if (refund) "refund" else "release")
            tool.execute(Args(tool.def, args), tools.ctx)
            assertTrue(judge.judge(name, args, Origin.Model(1)) is Decision.Required)
            val unattended = ConfirmDecision(tools, gate, autonomous = true,
                provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
            assertTrue(unattended.judge(name, args, Origin.Model(1)) is Decision.Denied)
        }
        assertEquals(1, port.releases); assertEquals(1, port.refunds)
        val readOnly = toolDispatcher(FakeScreen(), coins = port, runSpec = RunSpec(RunId("r"), "read", "c", readOnly = true))
        assertFalse(readOnly.definitions().any { it.name in setOf("hivo_coins_release", "hivo_coins_refund") })
    }
    @Test fun acceptAndDeclineRequireOneApprovalAndAreHiddenFromReadOnlyTasks() = runTest {
        val tools = toolDispatcher(FakeScreen(), coins = Port())
        val args = buildJsonObject { put("receipt", "00000000-0000-0000-0000-000000000001") }
        for (action in listOf("accept", "decline")) {
            val name = "hivo_coins_$action"
            val gate = ConfirmationGate()
            val judge = ConfirmDecision(tools, gate, autonomous = true)
            assertTrue(judge.judge(name, args, Origin.Model(1)) is Decision.Required)
            val request = tools.approvalRisk(name, args)!!.ask!!
            gate.declare(request, request.app); gate.decide(true, 1)
            assertEquals(Decision.Clear, judge.judge(name, args, Origin.Model(1)))
            val tool = CoinsSettlement(action)
            tool.execute(Args(tool.def, args), tools.ctx)
            assertTrue(judge.judge(name, args, Origin.Model(1)) is Decision.Required)
            val unattended = ConfirmDecision(tools, gate, autonomous = true,
                provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
            assertTrue(unattended.judge(name, args, Origin.Model(1)) is Decision.Denied)
        }
        val readOnly = toolDispatcher(FakeScreen(), coins = Port(), runSpec = RunSpec(RunId("r"), "read", "c", readOnly = true))
        assertFalse(readOnly.definitions().any { it.name in setOf("hivo_coins_accept", "hivo_coins_decline") })
    }
    @Test fun remindersRequireBothDueFieldsAndAreIndependentPerStage() {
        var disk = emptySet<String>()
        val reminder = CoinEscrowReminders({ disk }, { disk = it })
        val entry = CoinEntry("pay", "11111111", -30, "pay", receipt = "r", at = 1, status = "active",
            milestones = listOf(CoinMilestone(10, "No rule"), CoinMilestone(10, "Date only", 10L),
                CoinMilestone(10, "Rule only", atDue = "release")))
        assertTrue(reminder.collect(listOf(entry), 1).isEmpty())
        val staged = entry.copy(milestones = listOf(CoinMilestone(10, "First", 10L, "release", 0),
            CoinMilestone(20, "Second", 10L, "refund", 1)))
        assertEquals(2, reminder.collect(listOf(staged), 1).size)
        assertTrue(reminder.collect(listOf(staged), 1).isEmpty())
    }
    @Test fun reminderIsOnceAcrossRestartsAndProblemGoalContainsReceipt() {
        var disk = emptySet<String>()
        val entry = CoinEntry("pay", "11111111", -30, "pay", receipt = "receipt-id", at = 1,
            status = "active", milestones = listOf(CoinMilestone(30, "Delivery", 10 * 86400000L, "release")))
        val reminder = CoinEscrowReminders({ disk }, { disk = it })
        assertTrue(reminder.collect(listOf(entry), 9 * 86400000L - 1).isEmpty())
        assertEquals(1, reminder.collect(listOf(entry), 9 * 86400000L).size)
        assertTrue(CoinEscrowReminders({ disk }, { disk = it }).collect(listOf(entry), 9 * 86400000L).isEmpty())
        assertTrue(reminder.collect(listOf(entry.copy(receipt = "dispute", dispute = "case")), 9 * 86400000L).isEmpty())
        assertTrue(reminder.collect(listOf(entry.copy(receipt = "expired")), 10 * 86400000L).isEmpty())
        assertTrue(CoinEscrowGoals.dispute("receipt-id", "Help me write a reason").contains("kind=payment id=receipt-id"))
        assertTrue(CoinEscrowGoals.release("receipt-id", "Confirm delivery").contains("receipt=receipt-id"))
    }
    @Test fun signedHeldAndSettlementReceiptsPreserveT35CanonicalBytes() {
        val key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val base = CoinReceipt("id", "11111111", "22222222", 30, null,
            listOf(CoinSplit("22222222", 30, "receive")), "seat", 1)
        val old = """{"id":"id","from":"11111111","to":"22222222","amount":30,"post":null,"splits":[{"number":"22222222","amount":30,"kind":"receive"}],"note":"seat","at":1}"""
        for ((receipt, canonical) in listOf(base to old,
            base.copy(hold = true, status = "held", releaseAt = 10) to old.dropLast(1).plus(",\"hold\":true,\"status\":\"held\",\"releaseAt\":10}"),
            base.copy(kind = "release", original = "held-id") to old.dropLast(1).plus(",\"kind\":\"release\",\"original\":\"held-id\"}"),
            base.copy(status = "proposed", terms = CoinTerms(listOf(CoinMilestone(30, "Delivery", 10, "refund")))) to
                old.dropLast(1).plus(",\"terms\":{\"milestones\":[{\"amount\":30,\"what\":\"Delivery\",\"due\":10,\"atDue\":\"refund\"}]},\"status\":\"proposed\"}"),
            base.copy(kind = "release", original = "agreement-id", milestone = 1) to
                old.dropLast(1).plus(",\"kind\":\"release\",\"original\":\"agreement-id\",\"milestone\":1}"))) {
            val signature = java.security.Signature.getInstance("Ed25519").run {
                initSign(key.private); update(canonical.toByteArray()); sign()
            }
            val envelope = SignedCoinReceipt(receipt, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
            val publicKey = java.util.Base64.getEncoder().encodeToString(key.public.encoded)
            assertTrue(CoinSignatures.verify(envelope, publicKey))
            assertFalse(CoinSignatures.verify(envelope.copy(receipt = receipt.copy(amount = 31)), publicKey))
        }
    }

    @Test fun heldIncomingIsNotSpendableAndNotifiedOnlyOnce() {
        var seen: Set<String>? = emptySet()
        val incoming = CoinIncoming({ seen }, { seen = it })
        val held = CoinEntry("held", "22222222", 0, "receive", receipt = "receipt", at = 1,
            status = "proposed", heldAmount = 30)
        assertEquals(listOf(held), incoming.collect(listOf(held)))
        assertTrue(incoming.collect(listOf(held)).isEmpty())
        val released = CoinEntry("released", "22222222", 30, "receive", at = 2)
        assertEquals(listOf(released), incoming.collect(listOf(held.copy(status = "released"), released)))
    }
    @Test fun paymentDisputeNeedsExactOwnerApprovalInCommunityAndUnattendedIsDenied() = runTest {
        val number = object : com.wangzan101.mika.core.number.NumberPort {
            override val governance = com.wangzan101.mika.core.number.HubGovernancePort { _, _ -> error("must not send") }
            override val coins = Port()
            override fun inbox() = emptyList<com.wangzan101.mika.core.number.NumberPort.Item>()
            override suspend fun send(number: String, kind: String, subject: String, text: String, thread: String?) = Unit
        }
        val tools = toolDispatcher(FakeScreen(), number = number,
            runSpec = RunSpec(RunId("c"), "dispute", "c", computeMode = ComputeMode.Community))
        assertTrue(tools.definitions().any { it.name == "hivo_report" })
        val args = buildJsonObject { put("kind", "payment"); put("id", "receipt"); put("reason", "not delivered") }
        val read = tools.executeRead(com.wangzan101.mika.core.model.Part.ToolCall("report", "hivo_report", args.toString()))
        assertEquals(JsonPrimitive(false), read.content["ok"])
        val gate = ConfirmationGate(phoneOnly = true)
        val judge = ConfirmDecision(tools, gate, autonomous = true)
        assertTrue(judge.judge("hivo_report", args, Origin.Model(1)) is Decision.Required)
        val request = tools.approvalRisk("hivo_report", args)!!.ask!!
        gate.declare(request, request.app); gate.decide(ConfirmChoice.Once, 1, onPhone = true)
        assertEquals(Decision.Clear, judge.judge("hivo_report", args, Origin.Model(1)))
        assertTrue(judge.judge("hivo_report", args, Origin.Model(1)) is Decision.Required)
        val unattended = ConfirmDecision(tools, gate,
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        assertTrue(unattended.judge("hivo_report", args, Origin.Model(1)) is Decision.Denied)
    }

}
