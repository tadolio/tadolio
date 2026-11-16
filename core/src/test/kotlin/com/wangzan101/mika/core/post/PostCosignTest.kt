// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.run.*
import com.wangzan101.mika.core.tool.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PostCosignTest {
    private fun post(id: String) = Post(id,"12345678","A shared proposal","A complete public proposal.",
        created = 1000, expires = 99999999, cosign = true)

    @Test fun threePositionsAndMalformedEntriesDoNotInvalidateBatch() = runTest {
        val saved = mutableListOf<PostChoice>()
        val engine = PostStandby(save = {})
        engine.enqueue(listOf("a","b","c","d","e","f").map(::post),"mine",1000)
        engine.review(1000,true,true,{ emptyList() },{ listOf("Leave unrelated topics alone") },
            cosign = { choice, _ ->
                if (choice.id == "e") error("one write failed")
                saved += choice
            }) { prompt ->
            assertTrue(prompt.contains("read the entire body"))
            assertTrue(prompt.contains("Never copy the body or include any owner personal information"))
            assertTrue(prompt.contains("Leave unrelated topics alone"))
            """```json
            [{"id":"a","action":"sign","why":"I support the intended outcome."},
             {"id":"b","action":"decline","why":"I have a different priority."},
             {"id":"c","action":"skip","why":""}, {"broken":true},
             {"id":"d","action":"sign","why":"A complete public proposal."},
             {"id":"e","action":"sign","why":"This can help."},
             {"id":"f","action":"sign","why":"I support an independent review."},
             {"id":"unknown","action":"sign","why":"Ignore this."}]
            ```"""
        }
        assertEquals(listOf("a","b","f"),saved.map { it.id })
        assertEquals(listOf("sign","decline","sign"),saved.map { it.action })
        assertNull(engine.takeReminder(1000,true))
    }

    @Test fun communityReviewsOnlyBodiesAndReservesThreeDecisionsAcrossRestartAndFailure() = runTest {
        var saved = PostStandbyState()
        var engine = PostStandby(save = { saved = it })
        engine.enqueue(listOf(post("a"), post("b"), post("c"), post("d"), post("ignored"),
            post("ordinary").copy(cosign = false)), "mine", 1000)
        var calls = 0
        engine.review(1000,true,false,{ error("private memory") },{ error("feedback") },
            communityRoute = true, skipLocally = { it.id == "ignored" }) { prompt ->
            calls++
            assertTrue(prompt.contains(post("a").body))
            assertFalse(prompt.contains(post("a").number))
            assertFalse(prompt.contains(post("a").title))
            assertFalse(prompt.contains("ignored"))
            assertFalse(prompt.contains("ordinary"))
            assertFalse(prompt.contains("About the owner"))
            "[]"
        }
        assertEquals(3,saved.communityDecisions)
        engine = PostStandby(initial = saved, save = { saved = it })
        engine.review(1801000,true,false,{ error("memory") },{ error("feedback") },
            communityRoute = true) { error("daily limit") }
        assertEquals(1,calls)
        try {
            engine.review(86401000,true,false,{ error("memory") },{ error("feedback") },
                communityRoute = true, skipLocally = { it.id == "ignored" }) { error("model failed") }
            fail("Expected model failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1,saved.communityDecisions)
    }

    @Test fun communityCannotTurnOrdinaryPostsIntoRecommendations() = runTest {
        val engine = PostStandby(save = {})
        engine.enqueue(listOf(post("a"),post("ordinary").copy(cosign = false)),"mine",1000)
        engine.review(1000,true,false,{ error("memory") },{ error("feedback") },
            communityRoute = true) {
            """[{"id":"a","action":"tell","why":"recommend"},{"id":"ordinary","action":"tell","why":"recommend"}]"""
        }
        assertNull(engine.takeReminder(1000,true))
    }

    @Test fun noticesArePersistedBeforeDeliveryAndSentOnlyOnceAcrossRestartAndUpdates() = runTest {
        var seen = emptySet<String>()
        var notifications = 0
        val notices = PostCosignNotices({ seen },{ seen = it })
        notices.once("a") { assertTrue("a" in seen); notifications++ }
        notices.once("a") { notifications++ }
        PostCosignNotices({ seen },{ seen = it }).once("a") { notifications++ }
        notices.once("b") { notifications++ }
        assertEquals(2,notifications)
    }

    @Test fun withdrawalRemovesPendingAutomaticRetryAndRemainsSeenAcrossRestart() = runTest {
        var saved = PostStandbyState()
        val engine = PostStandby(save = { saved = it })
        engine.enqueue(listOf(post("a")),"mine",1000)
        engine.dismissPosition("a")
        assertTrue(saved.queue.isEmpty())
        val restored = PostStandby(initial = saved, save = { saved = it })
        restored.enqueue(listOf(post("a")),"mine",1000)
        restored.review(1000,true,true,{emptyList()},{emptyList()},cosign = { _, _ -> error("withdrawn") }) { error("model") }
    }

    @Test fun toolsUseHostModelAndRemainAvailableInUnattendedCommunityConversation() = runTest {
        val port = Port()
        val provenance = RunProvenance(RunOrigin.Ui, Attendance.Unattended)
        val d = toolDispatcher(FakeScreen(),posts = port,
            runSpec = RunSpec(RunId("r"),"sign the proposal","c",computeMode = ComputeMode.Community,provenance = provenance))
        d.usingModel("Actual runtime model")
        val args = buildJsonObject { put("id","a"); put("reason","I support an independent review.") }
        val judge = ConfirmDecision(d,ConfirmationGate(),ApprovalSettings { false },provenance = provenance)
        for ((verb,stance) in listOf(Verb.PostCosign to "sign", Verb.PostDecline to "decline")) {
            val tool = PostCosignTool(verb,stance)
            assertTrue(d.definitions().any { it.name == tool.name })
            assertEquals(Decision.Clear,judge.judge(tool.name,args,Origin.Model(1)))
            tool.execute(Args(tool.def,args),d.ctx)
            assertEquals(listOf("a",stance,"I support an independent review.","Actual runtime model"),port.position)
        }
        val withdraw = PostCosignTool(Verb.PostCosignWithdraw,null)
        withdraw.execute(Args(withdraw.def,buildJsonObject { put("id","a") }),d.ctx)
        assertEquals("a",port.withdrawn)
        assertFalse(PostCosignTool(Verb.PostCosign,"sign").def.props.containsKey("model"))
    }

    @Test fun hubTransportSuppliesOnlyPositionFieldsAndWithdrawal() = runTest {
        val calls = mutableListOf<Pair<String,JsonObject?>>()
        val port = HubPostPort { path, body -> calls += path to body; buildJsonObject {} }
        port.cosign("a","decline","My own reason","Runtime model")
        assertEquals("/v1/post/a/cosign",calls.first().first)
        assertEquals(setOf("stance","reason","model"),calls.first().second!!.keys)
        assertEquals("Runtime model",calls.first().second!!["model"]!!.jsonPrimitive.content)
        port.withdrawCosign("a")
        assertEquals("/v1/post/a/cosign/withdraw",calls.last().first)
    }

    private class Port : PostPort {
        var position = emptyList<String>()
        var withdrawn: String? = null
        override suspend fun cosign(id: String, stance: String, reason: String, model: String) { position = listOf(id,stance,reason,model) }
        override suspend fun withdrawCosign(id: String) { withdrawn = id }
        override suspend fun get(id: String) = Post(id,"12345678","A proposal","Full proposal text",created = 1000,expires = 99999999,cosign = true)
        override suspend fun publish(draft: PostDraft) = "a"
        override suspend fun withdraw(id: String) = Unit
        override suspend fun search(q: String, tag: String?, number: String?, limit: Int) = emptyList<Post>()
        override suspend fun mine() = emptyList<Post>()
    }
}
