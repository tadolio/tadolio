// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.agent.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.*
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.record.*
import com.wangzan101.mika.core.risk.*
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.testing.*
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommunitySensitiveTest {
    private val card = "4111 1111 1111 1111"
    private val identity = "11010519491231002X"
    private val key = "abcdef0123456789".repeat(4)
    private fun masked(value: String, original: String = value, kind: String? = null) {
        val safe = CommunitySensitive.redact(value)
        assertFalse(safe, safe.contains(original))
        assertTrue(safe, safe.contains("[redacted:${kind ?: ""}"))
    }
    @Test fun otpNeedsAnAdjacentKeywordAndDoesNotMaskDatesOrPhones() {
        for (label in listOf("验证码", "校验码", "动态码", "code", "OTP", "verification"))
            for (n in listOf("1234", "123456", "12345678")) masked("$label: $n", n, "otp")
        masked("123456\n验证码", "123456", "otp")
        masked("OTP\nplease wait\n123456", "123456", "otp")
        for (s in listOf("123456", "验证码: 123", "code 123456789", "验证码 13800138000", "OTP 2026-11-04", "postcode 123456", "电话 010-12345678"))
            assertEquals(s, s, CommunitySensitive.redact(s))
    }
    @Test fun bip39AllSupportedLengthsAndUnknownWords() {
        for (n in listOf(12,15,18,21,24)) masked(List(n) { "abandon" }.joinToString(" "),kind="mnemonic")
        for (n in listOf(11,13)) { val s=List(n) {"abandon"}.joinToString(" ");assertEquals(s,CommunitySensitive.redact(s)) }
        val ordinary="this extremely unusual sentence contains exactly twelve separate words about ordinary software"
        assertEquals(ordinary,CommunitySensitive.redact(ordinary))
        masked("seed phrase: not-in-word-list", "not-in-word-list", "mnemonic")
        masked("助记词：任意钱包词", "任意钱包词", "mnemonic")
    }
    @Test fun privateKeyBoundariesWifAndLabels() {
        masked(key,kind="private_key");masked("0x$key",kind="private_key")
        for (prefix in listOf("5","K","L")) masked(prefix+"a".repeat(50),kind="private_key")
        masked("private key: arbitrary-value", "arbitrary-value", "private_key")
        masked("private key: [redacted:fake] arbitrary-value", "arbitrary-value", "private_key")
        masked("private key\nfirst-line\nsecond-line", "second-line", "private_key")
        for (s in listOf("a".repeat(63), "a".repeat(65), "K"+"0".repeat(50), "M"+"a".repeat(50),"ordinary public value"))
            assertEquals(s,CommunitySensitive.redact(s))
    }
    @Test fun cardRequiresLengthAndLuhn() {
        for (s in listOf(card,card.replace(" ","-"),card.replace(" ",""),"4222222222222")) masked(s,kind="card")
        for (s in listOf("4111111111111112","13800138000","2026-11-04","12345678","0000000000000000")) assertEquals(s,CommunitySensitive.redact(s))
    }
    @Test fun chinaIdChecksDateProvinceAndChecksum() {
        masked(identity,kind="id")
        for (s in listOf("110105194912310021","99010519491231002X","11010519491331002X","123456789012345678")) assertEquals(s,CommunitySensitive.redact(s))
    }
    @Test fun everyTextFieldAndNestedInlineExitIsCovered() {
        val payload=buildJsonObject {
            put("elements",buildJsonArray { add(buildJsonObject {put("name","OTP")});add(buildJsonObject {put("name","123456")}) })
            put("screen",buildJsonObject {put("tree",buildJsonArray {add(buildJsonObject {put("t",key);put("d",card);put("h",identity)})})})
            put("extract","验证码 654321");put("query",key);put("collect",card)
            put("web",buildJsonObject {put("text","OTP 876543")})
        }.toString()
        val safe=CommunitySensitive.payload(payload)
        for (value in listOf("123456",key,card,identity,"654321","876543")) assertFalse(value,safe.contains(value))
        Json.parseToJsonElement(safe)
    }
    @Test fun escapedUnicodeAndSplitMnemonicNodesCannotLeak() {
        val escaped="""{"elements":[{"name":"\u9a8c\u8bc1\u7801"},{"name":"123456"}]}"""
        assertFalse(CommunitySensitive.payload(escaped).contains("123456"))
        val phrase=buildJsonObject {put("elements",JsonArray(List(12) {buildJsonObject {put("name","abandon")}}))}.toString()
        assertFalse(CommunitySensitive.payload(phrase).contains("abandon"))
    }
    @Test fun sensitiveObjectKeysAndEncodedJsonValuesAreAlsoCovered() {
        val raw=buildJsonObject {put("OTP 123456", "ordinary");put("nested", buildJsonObject {put("name", "OTP 654321")}.toString())}.toString()
        val safe=CommunitySensitive.payload(raw)
        assertFalse(safe.contains("123456"));assertFalse(safe.contains("654321"))
        Json.parseToJsonElement(safe)
    }
    @Test fun extractedDynamicKeysNumericValuesAndUncommonWebFieldsAreCovered() {
        val raw="""{"login_code":123456,"customHtmlText":"OTP 654321","private key":"unusual-secret","recovery phrase":"unusual-phrase"}"""
        val safe=CommunitySensitive.payload(raw)
        for (value in listOf("123456","654321","unusual-secret","unusual-phrase")) assertFalse(value,safe.contains(value))
        assertEquals("{\"amount\":123456,\"date\":\"2026-11-04\",\"phone\":\"13800138000\"}",
            CommunitySensitive.payload("""{"amount":123456,"date":"2026-11-04","phone":"13800138000"}"""))
    }
    @Test fun auxiliaryModelHasTheSameBoundary() = runTest {
        val model=fakeModel {answer("done")}
        CommunityModel(model).complete("BASE",listOf(Message(Role.User,listOf(Part.Text("OTP 123456"),Part.Image("AQID")),Author.User)),emptyList())
        assertFalse(model.requests.single().messages.toString().contains("123456"))
        assertTrue(model.requests.single().messages.any {m->m.parts.any {it is Part.Image}})
    }
    @Test fun labelValuesAndPasswordFieldsAreHidden() {
        for (label in listOf("助记词","seed phrase","recovery phrase","mnemonic","私钥","private key")) {
            val raw=buildJsonObject {put("elements",buildJsonArray {add(buildJsonObject {put("name",label)});add(buildJsonObject {put("name","arbitrary-secret")})})}.toString()
            assertFalse(label,CommunitySensitive.payload(raw).contains("arbitrary-secret"))
        }
        for (field in listOf("secret","password")) {
            val raw="""{"$field":true,"name":"passwordVALUE","t":"passwordVALUE","d":"passwordVALUE","h":"passwordVALUE"}"""
            assertFalse(CommunitySensitive.payload(raw).contains("passwordVALUE"))
        }
        assertFalse(CommunitySensitive.payload("""{"type":"password","value":"passwordVALUE"}""").contains("passwordVALUE"))
        assertEquals("""{"secret":false,"name":"ordinary-value"}""",CommunitySensitive.payload("""{"secret":false,"name":"ordinary-value"}"""))
    }
    private val clock="com.google.android.deskclock"
    private fun el(ref: String, text: String, top: Int, editable: Boolean=false) = UiElement(ref,"button",text,500,top+20,listOf(0,top,999,top+40),null,!editable,editable,false)
    private class Phone(val page: ScreenSnapshot):ScreenPort by FakeScreen() {
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
    @Test fun finalBoundaryCoversAllPartTypesAndOwnIsByteForByteUnchanged() = runTest {
        for (mode in ComputeMode.entries) {
            val model=fakeModel {answer("done");answer("done")}
            val dispatcher=toolDispatcher(Phone(page()),runSpec=spec(mode))
            val events=mutableListOf<RunEvent>()
            val caller=ModelCaller(model,dispatcher,null,SpeedFlags.OFF,RetryPolicy.NONE,events::add,{0L})
            caller.reset("BASE")
            val messages=listOf(
                Message(Role.User,listOf(Part.Text("OTP 123456")),Author.User),
                Message(Role.Tool,listOf(Part.ToolResult("c", """{"screen":{"elements":[{"name":"$card"}]},"text":"$identity"}""",false)),Author.Mika),
                Message(Role.Assistant,listOf(Part.ToolCall("c","type","""{"text":"$key"}"""),Part.Thinking("OTP 123456")),Author.Mika))
            caller.ask(messages,emptyList(),1);caller.ask(messages,emptyList(),2)
            if(mode==ComputeMode.Own) { assertEquals(messages,model.requests.last().messages);assertTrue(events.none {it is RunEvent.State && it.name=="community_sensitive_redacted"}) }
            else {
                val sent=model.requests.last().messages.toString()
                for(s in listOf("123456",card,identity,key))assertFalse(s,sent.contains(s))
                assertEquals(1,events.count {it is RunEvent.State && it.name=="community_sensitive_redacted"})
            }
            assertTrue(messages.toString().contains("123456")) // original append-only history untouched
        }
    }
    @Test fun eachNewUserInterjectionGetsItsOwnNoticeWithoutRescanningHistory() {
        var notices=0;val boundary=CommunityMessages {notices++}
        val goal=Message(Role.User,listOf(Part.Text("OTP 123456")),Author.User)
        val steer=Message(Role.User,listOf(Part.Text("私钥: secret-value")),Author.User)
        val first=boundary.outgoing(listOf(goal)).single()
        val second=boundary.outgoing(listOf(goal,steer))
        assertSame(first,second.first());assertEquals(2,notices)
        boundary.outgoing(listOf(goal,steer));assertEquals(2,notices)
    }
    @Test fun communitySendDeleteSubmitInstallRequirePhoneGrantAndAreSingleUse() = runTest {
        for((label,action) in listOf("发送" to IrrevAction.send,"删除" to IrrevAction.delete,"提交" to IrrevAction.submit,"安装" to IrrevAction.install,"下单" to IrrevAction.order)) {
            val p=page(label).let { if(action==IrrevAction.order) it.copy(elements=it.elements+el("@e4","¥25",700)) else it };val phone=Phone(p);val d=toolDispatcher(phone,runSpec=spec())
            d.executeRead(call("phone_observe"))
            val gate=ConfirmationGate(phoneOnly=true)
            val executor=ActionExecutor(d,OperationGuards(40),gate,{},ActionRecorder(RunSink.NONE),approvals=ApprovalSettings {true},autonomous=true)
            val tap=call("phone_tap","""{"ref":"@e2","snapshotId":"s1"}""")
            assertEquals(label,"confirmation_required",(executor.run(tap,Origin.Model(1)) as ActionOutcome.Refused).code)
            assertTrue(phone.taps.isEmpty())
            val req=ConfirmationRequest(action,"对象标题","hello",if(action==IrrevAction.order) "¥25" else null,clock)
            assertEquals(DeclareResult.Pending,gate.declare(req,clock))
            gate.decide(ConfirmChoice.Once,1) // MCP/CLI cannot mint a phone grant
            assertFalse(gate.hasGrant)
            gate.decide(ConfirmChoice.Once,1,onPhone=true)
            assertTrue(label,executor.run(tap,Origin.Model(2)) is ActionOutcome.Ran)
            assertEquals(1,phone.taps.size)
            assertEquals("confirmation_required",(executor.run(tap,Origin.Model(3)) as ActionOutcome.Refused).code)
        }
    }
    @Test fun freeDeclarationCannotBypassAnOnScreenAmount() {
        val gate=ConfirmationGate(phoneOnly=true)
        gate.declare(ConfirmationRequest(IrrevAction.submit,"对象标题","hello",null,clock),clock)
        gate.decide(ConfirmChoice.Once,1,onPhone=true)
        assertTrue(gate.permits(Irreversible(IrrevAction.submit,"提交"),page().copy(elements=page().elements+el("@e4","¥25",700)),2) is Permit.Mismatch)
    }
    @Test fun installOnlyEnablesCommunityDetectionForTapAndKeyboardTargets() {
        val p=page("Install")
        val args=buildJsonObject {put("ref","@e2")}
        assertNull(IrreversibleDetector.classify(GateTarget.Tap,args,p))
        assertEquals(IrrevAction.install,IrreversibleDetector.classifyCommunity(GateTarget.Tap,args,p)!!.action)
        assertNull(IrreversibleDetector.classifyCommunity(GateTarget.Tap,args,page("Installation history")))
        val focused=p.copy(elements=p.elements.map {it.copy(focused=it.ref=="@e2")})
        assertEquals(IrrevAction.install,IrreversibleDetector.classifyCommunity(GateTarget.Key,buildJsonObject {put("key","KEYCODE_ENTER")},focused)!!.action)
    }
    @Test fun otherCategoryAndPaymentNeverBypassThePhoneGate() {
        val gate=ConfirmationGate(phoneOnly=true)
        val r=ConfirmationRequest(IrrevAction.other,"对象标题","hello",null,clock)
        gate.declare(r,clock);gate.decide(ConfirmChoice.Once,1,onPhone=true)
        assertEquals(Permit.Allowed,gate.permits(Irreversible(IrrevAction.other,"x"),page(),2))
        assertEquals(DeclareResult.Unsupported,gate.declare(r.copy(action=IrrevAction.pay),clock))
        assertEquals(Permit.NoGrant,gate.permits(Irreversible(IrrevAction.pay,"x"),page(),2))
    }
    @Test fun missingGateAndDirectAutonomyStillFailClosedInCommunity() = runTest {
        val d=toolDispatcher(Phone(page()),runSpec=spec());d.executeRead(call("phone_observe"))
        val decision=ConfirmDecision(d,null,autonomous=true,approvals=ApprovalSettings {true})
        assertTrue(decision.judge("phone_tap",buildJsonObject {put("ref","@e2");put("snapshotId","s1")},Origin.Model(1)) is Decision.Required)
    }
    @Test fun ownFreeIrreversibleActionsKeepOriginalBehavior() = runTest {
        for(label in listOf("发送","删除","提交","安装")) {
            val phone=Phone(page(label));val d=toolDispatcher(phone,runSpec=spec(ComputeMode.Own));d.executeRead(call("phone_observe"))
            val executor=ActionExecutor(d,OperationGuards(40),ConfirmationGate(),{},ActionRecorder(RunSink.NONE))
            assertTrue(label,executor.run(call("phone_tap","""{"ref":"@e2","snapshotId":"s1"}"""),Origin.Model(1)) is ActionOutcome.Ran)
        }
    }
    @Test fun sensitiveInputUsesTheNormalExecutorInBothModes() = runTest {
        for(mode in ComputeMode.entries) for(text in listOf(card,identity,key,"OTP 123456",List(12){"abandon"}.joinToString(" "))) {
            val phone=Phone(page());val d=toolDispatcher(phone,runSpec=spec(mode));d.executeRead(call("phone_observe"))
            val executor=ActionExecutor(d,OperationGuards(40),ConfirmationGate(),{},ActionRecorder(RunSink.NONE))
            val result=executor.run(call("phone_type_text",buildJsonObject {put("ref","@e3");put("snapshotId","s1");put("text",text)}.toString()),Origin.Model(1))
            assertTrue(result is ActionOutcome.Ran)
            assertEquals(listOf(text),phone.inputs)
        }
    }

    @Test fun communityConfirmationDefinitionAcceptsFreeActionsButOwnDoesNotChange() = runTest {
        for(mode in ComputeMode.entries) {
            val d=toolDispatcher(Phone(page()),runSpec=spec(mode))
            val def=d.definitions().first {it.name=="request_confirmation"}
            val schema=Json.parseToJsonElement(def.parametersJsonSchema).jsonObject
            assertEquals(mode==ComputeMode.Own,schema["required"]!!.jsonArray.any {it.jsonPrimitive.content=="amount"})
            assertEquals(mode==ComputeMode.Community,def.parametersJsonSchema.contains("\"install\""))
            val result=d.executeRead(call("request_confirmation","""{"action":"delete","target":"对象标题","content":"hello","app":"$clock"}"""))
            assertEquals(mode==ComputeMode.Community,result.outcome is Outcome.Confirm)
        }
    }
    @Test fun remoteAnswersCannotResolvePhoneConfirmationEvenForUiOriginTask() = runTest {
        val d=toolDispatcher(Phone(page()),runSpec=spec());val gate=ConfirmationGate(phoneOnly=true)
        val user=UserExchange(d,gate,null,1000,{null},{},{},{0L})
        user.begin("test",RunProvenance())
        val pending=async {user.confirm(Outcome.Confirm(ConfirmationRequest(IrrevAction.delete,"对象标题","hello",null,clock),clock),1,TaskState.Running(1,"test"))}
        runCurrent()
        assertFalse(user.answerConfirmation(ConfirmChoice.Once))
        assertFalse(user.answerConfirmation(ConfirmChoice.Once,user.activeAsk!!.askId))
        assertFalse(gate.hasGrant)
        assertTrue(user.answerConfirmation(ConfirmChoice.Once,onPhone=true))
        assertTrue(pending.await().content["approved"]!!.jsonPrimitive.boolean)
        assertTrue(gate.hasGrant)
    }
}
