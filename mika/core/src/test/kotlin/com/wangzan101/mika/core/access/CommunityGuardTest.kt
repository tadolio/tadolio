// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.agent.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.testing.*
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommunityGuardTest {
    private val clock = "com.google.android.deskclock"
    private val bank = "com.eg.android.AlipayGphone"
    private fun spec(mode: ComputeMode = ComputeMode.Community) = RunSpec(RunId("test"), "test", "test", computeMode = mode)
    private class Phone(var pkg: String?, var owners: List<String?> = listOf(pkg)) : ScreenPort by FakeScreen() {
        var during: (() -> Unit)? = null
        var readCount = 0
        val fake = FakeScreen()
        override suspend fun foregroundPackages() = owners
        override suspend fun snapshot(): ScreenSnapshot {
            readCount++
            val captured = ScreenSnapshot("p$readCount", pkg, listOf(UiElement("@e1", "text", "SECRET_SCREEN", 1, 1,
                listOf(0,0,100,100), null, false, false, false)), 100, 100)
            during?.invoke()
            return captured
        }
        override suspend fun peek() = snapshot()
        override suspend fun tapXY(x: Int, y: Int) = fake.tapXY(x,y)
        override suspend fun openApp(packageName: String, cold: Boolean) = fake.openApp(packageName,cold)
    }
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c",name,args)
    private suspend fun denied(body: suspend () -> Unit) {
        try { body(); fail("operation must be denied") } catch (e: AppAccessDenied) { assertEquals(CommunityPolicy.ERROR,e.code) }
    }
    private fun hidden(r: ToolExecution) {
        assertEquals(setOf("message","package"), r.content.keys)
        assertEquals(CommunityPolicy.HIDDEN,r.content["message"]!!.jsonPrimitive.content)
        assertFalse(r.content.toString().contains("SECRET_SCREEN"))
        assertTrue(r.images.isEmpty()); assertNull(r.frame); assertTrue(r.observations.isEmpty())
    }

    @Test fun privacyDenylistAllowsSettingsLauncherSystemImeAndUnknownApps() {
        for (pkg in listOf(clock, "com.android.settings", "com.android.systemui", "com.android.launcher3",
                "com.google.android.inputmethod.latin", "unknown.app", clock + ".evil"))
            assertTrue(pkg, CommunityPolicy.allowed(pkg))
        for (pkg in listOf(null, "com.tencent.mm", "com.eg.android.AlipayGphone", "com.android.mms", "com.tadolio.app"))
            assertFalse(pkg, CommunityPolicy.allowed(pkg))
    }
    @Test fun settingsAndLauncherCanBeReadAndTapped() = runTest {
        for (pkg in listOf("com.android.settings", "com.android.launcher3", "com.android.systemui", "com.google.android.inputmethod.latin")) {
            val p = Phone(pkg)
            val port = CommunityScreenPort(p)
            assertFalse(port.snapshot().communityHidden)
            port.tapXY(1, 1)
            assertEquals(1, p.fake.calls.size)
        }
    }
    @Test fun userPrivacyClassificationsAndManualBlocksAreLive() = runTest {
        val pkg = "unknown.app"
        var state = AppAccessState()
        val p = object : ScreenPort by Phone(pkg) {
            override val appAccess = AppAccessPolicy { state }
        }
        val port = CommunityScreenPort(p)
        assertFalse(port.snapshot().communityHidden)
        for (category in listOf(AppCategory.Finance, AppCategory.Social)) {
            state = AppAccessState(classifications = mapOf(pkg to AppClassification(category, manual = true)))
            assertTrue(port.snapshot().communityHidden)
            denied { port.tapXY(1, 1) }
            denied { port.openApp(pkg) }
        }
        state = AppAccessState(blocked = setOf(pkg))
        assertTrue(port.snapshot().communityHidden)
        denied { port.openApp(pkg) }
        state = AppAccessState(classifications = mapOf(pkg to AppClassification(AppCategory.Social)))
        assertFalse(port.snapshot().communityHidden)
    }
    @Test fun everyScreenExitIsPlaceholderForPrivacyApps() = runTest {
        val d=toolDispatcher(Phone(bank), visionEnabled=true, recordFrames=true, inlineScreen=true, adaptiveVision=true,runSpec=spec())
        val names = listOf("phone_observe","phone_list_elements","phone_find","phone_query","phone_extract","phone_collect","phone_diagnose","phone_wait","phone_screenshot","phone_describe","phone_find_visually","view_past_screens")
        for (name in names) {
            val result = d.executeRead(call(name))
            assertEquals(name, CommunityPolicy.HIDDEN, result.content["message"]?.jsonPrimitive?.content)
            hidden(result)
        }
        assertFalse(d.seesImages); assertNull(d.vision)
    }
    @Test fun missingOwnerAndPrivacyOverlayAreHidden() = runTest {
        for (owners in listOf(listOf(null),emptyList(),listOf(clock,bank))) {
            val phone=Phone(clock,owners)
            assertTrue(CommunityScreenPort(phone).snapshot().communityHidden)
        }
    }
    @Test fun changeDuringReadDiscardsAllCapturedText() = runTest {
        val p=Phone(clock);p.during={p.pkg=bank;p.owners=listOf(bank)}
        val s=CommunityScreenPort(p).snapshot()
        assertTrue(s.communityHidden);assertEquals(bank,s.packageName);assertTrue(s.elements.isEmpty());assertTrue(s.windows.isEmpty())
    }
    @Test fun contentOrGlobalGenerationChangeWithAllowedOwnershipRemainsVisible() {
        val s=ScreenSnapshot("p",clock,emptyList(),100,100,changedDuringRead=true)
        assertFalse(CommunityPolicy.sanitize(s,listOf(clock),listOf(clock)).communityHidden)
    }
    @Test fun everyNodeMustBelongToTheCapturedAllowedWindow() {
        assertTrue(CommunityPolicy.ownsNode(7,clock,7,clock))
        assertFalse(CommunityPolicy.ownsNode(7,clock,8,clock))
        assertFalse(CommunityPolicy.ownsNode(7,clock,7,bank))
        assertFalse(CommunityPolicy.ownsNode(7,clock,7,null))
        assertFalse(CommunityPolicy.ownsNode(7,null,7,null))
        assertFalse(CommunityPolicy.ownsNode(7,bank,7,bank))
    }
    @Test fun ownershipStillFailsClosedAndSystemWindowTypesAreAllowed() {
        val window = UiWindowTree(1,"application",0,null,clock,true,true,emptyList())
        val s = ScreenSnapshot("p",clock,emptyList(),100,100,changedDuringRead=true,windows=listOf(window))
        assertSame(s,CommunityPolicy.sanitize(s,listOf(clock),listOf(clock)))
        for ((before,after) in listOf(listOf(clock) to listOf(bank), listOf(bank) to listOf(clock),
                listOf(clock) to listOf("com.google.android.calculator"), emptyList<String?>() to listOf(clock))) {
            assertTrue(CommunityPolicy.sanitize(s,before,after).communityHidden)
        }
        for (type in listOf("system", "input_method", "other"))
            assertFalse(CommunityPolicy.sanitize(s.copy(windows=listOf(window.copy(type=type))),listOf(clock),listOf(clock)).communityHidden)
        for (w in listOf(window.copy(packageName=bank),window.copy(packageName=null)))
            assertTrue(CommunityPolicy.sanitize(s.copy(windows=listOf(w)),listOf(clock),listOf(clock)).communityHidden)
        assertTrue(CommunityPolicy.sanitize(s.copy(communityHidden=true),listOf(clock),listOf(clock)).communityHidden)
    }
    @Test fun appListDeclaresCommunityFilteringWithoutDisclosingFilteredApps() = runTest {
        val phone = object : ScreenPort by Phone(clock) {
            override suspend fun listApps() = listOf(clock to "Clock", bank to "PRIVATE_BANK")
        }
        val community = toolDispatcher(phone,runSpec=spec()).executeRead(call("phone_list_apps")).content
        assertEquals(JsonPrimitive(true),community["communityFiltered"])
        assertTrue(community["note"]!!.jsonPrimitive.content.contains("社区模式"))
        assertFalse(community.toString().contains(bank));assertFalse(community.toString().contains("PRIVATE_BANK"))
        val own = toolDispatcher(phone,runSpec=spec(ComputeMode.Own)).executeRead(call("phone_list_apps")).content
        assertFalse("communityFiltered" in own);assertFalse("note" in own)
        assertTrue(own.toString().contains(bank))
    }
    @Test fun allowedScreenKeepsTextAndOwnKeepsAllContent() = runTest {
        val allowed=toolDispatcher(Phone(clock),runSpec=spec()).executeRead(call("phone_observe"))
        assertTrue(allowed.content.toString().contains("SECRET_SCREEN"))
        val own=toolDispatcher(Phone(bank),runSpec=spec(ComputeMode.Own)).executeRead(call("phone_observe"))
        assertTrue(own.content.toString().contains("SECRET_SCREEN"))
    }
    @Test fun launchCopyLinkAndShareValidateTargets() = runTest {
        val p=Phone(clock); val g=CommunityScreenPort(p)
        denied {g.openApp(bank)};denied {g.openAppCopy(bank,10)};denied {g.shareText(bank,"x")}
        denied {g.openLink("android.settings.SETTINGS",null,null)}
        denied {g.openLink(null,"https://example.com",bank)}
        denied {g.systemCommand("am start -n private.bank/.Main")}
        assertTrue(p.fake.calls.isEmpty())
        g.openApp(clock); assertEquals(1,p.fake.calls.size)
    }
    @Test fun allowedTargetCannotAuthorizeInputOnBlockedForeground() = runTest {
        val p=Phone(bank);val g=CommunityScreenPort(p)
        denied {g.tapXY(1,1)};denied {g.typeAt(1,1,"x")};denied {g.back()};denied {g.openApp(clock)}
        assertTrue(p.fake.calls.isEmpty())
    }
    @Test fun shellEventsImagesAndOcrNeverReachUnderlyingPort() = runTest {
        val g=CommunityScreenPort(Phone(clock))
        assertNull(g.screenshotPng());assertNull(g.readVisibleText());assertNull(g.frameGrid())
        assertNull(g.nextEvent("notification",null,1));assertTrue(g.watchEvents("notification",null,1,10).isEmpty())
    }
    @Test fun allChangingActionsUseFreshForegroundInExecutor() = runTest {
        val p=Phone(bank);val d=toolDispatcher(p,runSpec=spec())
        val executor=ActionExecutor(d,OperationGuards(40),null,gate={},recorder=ActionRecorder(RunSink.NONE,System::currentTimeMillis))
        val r=executor.run(call("phone_tap", """{"x":1,"y":1}"""),Origin.Model(1))
        assertTrue(r is ActionOutcome.Refused);assertTrue(p.fake.calls.isEmpty())
        assertEquals(CommunityPolicy.ERROR,(r as ActionOutcome.Refused).code)
    }
    @Test fun modeParametersCannotChangeFrozenPolicy() = runTest {
        val d=toolDispatcher(Phone(bank),runSpec=spec())
        hidden(d.executeRead(call("phone_observe", """{"computeMode":"Own","community":false}""")))
        assertEquals(ComputeMode.Community,d.computeMode)
        for (name in listOf("settings_set","memory_search","memory_save","skill_read","history_read","phone_system_command","phone_run","phone_event","spawn_agent"))
            assertFalse(name,d.definitions().any {it.name==name})
        d.executeRead(call("settings_set", """{"key":"computeMode","value":"Own"}"""))
        assertEquals(ComputeMode.Community,d.computeMode)
    }
    @Test fun recallNeverReturnsEarlierAllowedTextAfterSwitch() = runTest {
        val p=Phone(clock);val d=toolDispatcher(p,runSpec=spec())
        d.executeRead(call("phone_observe"));p.pkg=bank;p.owners=listOf(bank)
        hidden(d.executeRead(call("view_past_screens", """{"ids":[1]}""")))
    }
    @Test fun outgoingModelBoundaryStripsImagesEvenIfAFutureToolAddsOne() = runTest {
        for (mode in ComputeMode.entries) {
            val m=fakeModel(vision=true) { answer("done") }
            val d=toolDispatcher(Phone(clock),runSpec=spec(mode))
            val caller=ModelCaller(m,d,null,SpeedFlags.OFF,RetryPolicy.NONE,{}, {0L})
            caller.reset("BASE")
            caller.ask(listOf(Message(Role.User,listOf(Part.Text("task"),Part.Image("AQID")),Author.User)),emptyList(),1)
            assertEquals(mode==ComputeMode.Own,m.requests.single().messages.any {it.parts.any {p->p is Part.Image}})
        }
    }
    @Test fun isolatedBrowserCannotSendImagesToTheVerifier() = runTest {
        for (mode in ComputeMode.entries) {
            var captures = 0
            val web = object : com.wangzan101.mika.core.cap.web.WebPort by com.wangzan101.mika.core.cap.web.FakeWebPort() {
                override suspend fun screenshotPng(): ByteArray { captures++; return byteArrayOf(1,2,3) }
            }
            val d=toolDispatcher(Phone(clock),web=web,runSpec=spec(mode))
            d.executeRead(call("web_observe"))
            val evidence=d.evidence(withImage=true)
            assertEquals(mode==ComputeMode.Own,evidence.freshImage != null)
            assertEquals(if (mode==ComputeMode.Own) 1 else 0,captures)
        }
    }
    @Test fun mcpAndBatchRequestsCannotOverrideTheModeOrForegroundGate() = runTest {
        val p=Phone(bank);val d=toolDispatcher(p,runSpec=spec())
        val executor=ActionExecutor(d,OperationGuards(40),null,gate={},recorder=ActionRecorder(RunSink.NONE))
        for (origin in listOf(Origin.McpTool(1),Origin.Batch(1),Origin.Script(1),Origin.Replay)) {
            val r=executor.run(call("phone_tap", """{"x":1,"y":1,"computeMode":"Own"}"""),origin)
            assertEquals(CommunityPolicy.ERROR,(r as ActionOutcome.Refused).code)
        }
        assertEquals(ComputeMode.Community,d.computeMode);assertTrue(p.fake.calls.isEmpty())
    }
    @Test fun forbiddenAppCannotReappearThroughUserAppAccessExceptions() = runTest {
        val inner=AppAccessScreenPort(Phone(bank),AppAccessPolicy {AppAccessState(blocked=setOf(bank))},packages={listOf(bank)})
        val s=CommunityScreenPort(inner).snapshot()
        assertTrue(s.communityHidden);assertTrue(s.elements.isEmpty())
    }
    @Test fun communityPromptSnapshotIsSeparateFromOwn() {
        val d=toolDispatcher(Phone(clock),inlineScreen=true,runSpec=spec());d.expand("phone")
        val specs=d.offeredNow(all=true)
        val prompt=SystemPrompt.assemble(SystemPrompt.identity(),d.promptSections(specs,all=true))
        PromptSurface.check("community-phone",PromptSurface.system(prompt,specs))
        val own=toolDispatcher(Phone(clock));assertFalse(own.promptSections(own.offeredNow()).any {"Community node mode" in it})
    }
    @Test fun legacyRunEntryAlsoDropsPriorTurnsAndReplayFlags() = runTest {
        val m=fakeModel { answer("done") }
        val d=toolDispatcher(Phone(clock),runSpec=spec())
        val loop=AgentLoop(m,d,"BASE",flags=SpeedFlags.OFF.copy(replay=true,lightSteps=true,adaptiveVision=true),reviewFinalAnswer=false)
        assertFalse(loop.flags.replay);assertFalse(loop.flags.lightSteps);assertFalse(loop.flags.adaptiveVision)
        loop.run("task",listOf(Turn("PRIVATE_PRIOR","PRIVATE_ANSWER","succeeded")))
        assertFalse(m.requests.single().messages.toString().contains("PRIVATE_PRIOR"))
    }

    @Test fun priorTurnsAndPersonalMemoryAreNotInjectedEvenByAnotherHost() = runTest {
        val m=fakeModel { answer("done") }
        val memory= com.wangzan101.mika.core.memory.MemoryStore(object : com.wangzan101.mika.core.memory.MemoryPersistence {
            override fun load()=listOf(com.wangzan101.mika.core.memory.MemoryItem(
                "m", com.wangzan101.mika.core.memory.MemoryKind.fact, "PRIVATE_MEMORY", emptyList(),
                com.wangzan101.mika.core.memory.MemorySource("manual",atMs=0), com.wangzan101.mika.core.memory.MemoryStatus.active,
                createdAt=0, updatedAt=0))
            override fun save(items: List<com.wangzan101.mika.core.memory.MemoryItem>) {}
        }, clock={0L}, ids={"memory-id"})
        val d=toolDispatcher(Phone(clock),memory=memory,runSpec=spec())
        AgentLoop(m,d,"BASE",memory=memory,reviewFinalAnswer=false).run(spec().copy(priorTurns=listOf(Turn("PRIVATE_PRIOR","PRIVATE_ANSWER","succeeded"))),RunInbox())
        val sent=m.requests.single().messages.toString()
        assertFalse(sent.contains("PRIVATE_MEMORY"));assertFalse(sent.contains("PRIVATE_PRIOR"));assertFalse(sent.contains("PRIVATE_ANSWER"))
        assertFalse(m.requests.single().tools.any {it.name.startsWith("memory_")})
    }
}

