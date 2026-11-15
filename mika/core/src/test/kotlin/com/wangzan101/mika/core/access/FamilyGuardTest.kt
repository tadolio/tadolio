// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access
import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.*
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.testing.*
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class FamilyGuardTest {
    private val clock = "com.google.android.deskclock"
    private fun el(ref: String, text: String, top: Int, editable: Boolean=false) = UiElement(ref,"button",text,500,top+20,listOf(0,top,999,top+40),null,!editable,editable,false)
    private class Phone(var page: ScreenSnapshot):ScreenPort by FakeScreen() {
        val taps=mutableListOf<String>()
        val inputs=mutableListOf<String>()
        override suspend fun foregroundPackages()=listOf(page.packageName)
        override suspend fun snapshot()=page
        override suspend fun peek()=page
        override suspend fun tapRef(ref:String,snapshotId:String):ActionResult {taps+=ref;return ActionResult.Ok()}
        override suspend fun typeText(ref:String?,snapshotId:String?,text:String):ActionResult {inputs+=text;return ActionResult.Ok()}
    }
    private fun page(label:String="删除")=ScreenSnapshot("s1",clock,listOf(el("@e1","对象标题",10),el("@e2",label,600),el("@e3","hello",800,true)),1000,1000)
    private fun spec(mode:ComputeMode=ComputeMode.Community)=RunSpec(RunId("test"),"test","test",computeMode=mode)
    private fun call(name:String,args:String="{}")=Part.ToolCall("c",name,args)
    @Test fun approvalAllowsOnlyOriginalOperationAndRemoteOriginCannotAsk() = runTest {
        val phone=Phone(page("删除")); val d=toolDispatcher(phone,runSpec=spec(ComputeMode.Own));d.executeRead(call("phone_observe"))
        var asks=0
        val approvals=object: ApprovalSettings {
            override fun autonomous()=false
            override val familyRequired=true
            override suspend fun familyApprove(digest:String,category:String):Boolean {asks++;return true}
        }
        val args=buildJsonObject {put("ref","@e2");put("snapshotId","s1")}
        assertEquals(Decision.Clear,ConfirmDecision(d,ConfirmationGate(),approvals=approvals).judge("phone_tap",args,Origin.Model(1)))
        assertEquals(1,asks)
        for (source in listOf(RunOrigin.Mcp, RunOrigin.Channel)) {
            val remote=ConfirmDecision(d,ConfirmationGate(),approvals=approvals,provenance=RunProvenance(origin=source))
            assertTrue(remote.judge("phone_tap",args,Origin.Model(2)) is Decision.Denied)
        }
        assertEquals(1,asks)
    }

    @Test fun familyGuardRunsBeforeEverySensitiveOperationEvenWithAutonomy() = runTest {
        for(label in listOf("付款", "转账", "安装", "修改密码", "两步验证", "Account security", "密碼設定")) {
            val phone=Phone(page(label)); val d=toolDispatcher(phone,runSpec=spec(ComputeMode.Own));d.executeRead(call("phone_observe"))
            var called=0
            val approvals=object: ApprovalSettings {
                override fun autonomous()=true
                override val familyRequired=true
                override suspend fun familyApprove(digest:String,category:String):Boolean { called++; assertEquals(64,digest.length); return false }
            }
            val decision=ConfirmDecision(d,ConfirmationGate(),approvals=approvals,autonomous=true)
            val result=decision.judge("phone_tap",buildJsonObject {put("ref","@e2");put("snapshotId","s1")},Origin.Model(1))
            assertTrue(label,result is Decision.Denied);assertEquals(label,1,called);assertTrue(phone.taps.isEmpty())
        }
    }
    @Test fun plainOtpTypingCannotSkipFamilyByOmittingEnter() = runTest {
        val phone=Phone(page().copy(elements=listOf(el("@e1","验证码",10),el("@e3","",800,true))))
        val d=toolDispatcher(phone,runSpec=spec(ComputeMode.Own));d.executeRead(call("phone_observe"))
        var called=false
        val approvals=object: ApprovalSettings {
            override fun autonomous()=false
            override val familyRequired=true
            override suspend fun familyApprove(digest:String,category:String):Boolean {called=true;return false}
        }
        val result=ConfirmDecision(d,ConfirmationGate(),approvals=approvals).judge("phone_type_text",buildJsonObject {put("ref","@e3");put("snapshotId","s1");put("text","123456")},Origin.Model(1))
        assertTrue(result is Decision.Denied);assertTrue(called);assertTrue(phone.inputs.isEmpty())
    }
    @Test fun changingTargetDuringFamilyWaitRefusesEvenAnApproval() = runTest {
        val phone=Phone(page("删除"));val d=toolDispatcher(phone,runSpec=spec(ComputeMode.Own));d.executeRead(call("phone_observe"))
        val approvals=object: ApprovalSettings {
            override fun autonomous()=false
            override val familyRequired=true
            override suspend fun familyApprove(digest:String,category:String):Boolean {phone.page=page("另一位收款人");return true}
        }
        assertTrue(ConfirmDecision(d,ConfirmationGate(),approvals=approvals).judge("phone_tap",buildJsonObject {put("ref","@e2");put("snapshotId","s1")},Origin.Model(1)) is Decision.Denied)
    }
    @Test fun fiveCategoriesStaySeparateAndPrivateActionsNeverReachFamily() = runTest {
        for ((label, category) in listOf("付款" to "payment", "转账" to "transfer", "Transfer money" to "transfer", "轉帳" to "transfer", "安装" to "install", "安裝" to "install", "修改密码" to "security")) {
            val phone = Phone(page(label)); val d = toolDispatcher(phone, runSpec=spec(ComputeMode.Own)); d.executeRead(call("phone_observe"))
            val asked = mutableListOf<String>()
            val selected = FamilyPolicy().changed(FamilyCategory.Transfer, FamilyMode.Approve)
            val approvals = object : ApprovalSettings {
                override fun autonomous() = true
                override val familyRequired = true
                override fun familyMode(category: String) = selected.mode(category)
                override suspend fun familyApprove(digest: String, category: String): Boolean { asked += category; return false }
            }
            val result = ConfirmDecision(d, ConfirmationGate(), approvals=approvals, autonomous=true)
                .judge("phone_tap", buildJsonObject { put("ref", "@e2"); put("snapshotId", "s1") }, Origin.Model(1))
            assertEquals(label, if (category == "transfer") listOf("transfer") else emptyList<String>(), asked)
            assertEquals(label, category == "transfer", result is Decision.Denied)
        }
    }
    @Test fun notifyKeepsLocalPaymentGateAndDoesNotNotifyBlockedActions() = runTest {
        val phone = Phone(page("付款")); val d = toolDispatcher(phone, runSpec=spec(ComputeMode.Own)); d.executeRead(call("phone_observe"))
        var notices = 0
        val approvals = object : ApprovalSettings {
            override fun autonomous() = false
            override val familyRequired = true
            override fun familyMode(category: String) = if (category == "pay") FamilyMode.Notify else FamilyMode.Private
            override suspend fun familyApprove(digest: String, category: String): Boolean = error("notify must not wait")
            override fun familyNotify(category: String) { notices++ }
        }
        val decision = ConfirmDecision(d, ConfirmationGate(), approvals=approvals)
        val result = decision.judge("phone_tap", buildJsonObject { put("ref", "@e2"); put("snapshotId", "s1") }, Origin.Model(1))
        assertTrue(result is Decision.Required); assertEquals(0, notices)
        val pending = decision.takeNotice()
        decision.noteResult(pending, false); assertEquals(0, notices)
        decision.noteResult(pending, true); assertEquals(1, notices)
    }
}
