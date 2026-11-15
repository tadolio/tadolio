// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.post.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PostReviewBoundaryTest {
    @Test fun cosignModelComesFromActualRuntime() = runBlocking {
        for (hybrid in listOf(false,true)) {
            val engine = PostStandby(save = {})
            engine.enqueue(listOf(post().copy(cosign = true)),"mine",1000)
            val model = object : ChatModel {
                override val name = "Actual model from runtime"
                override val capabilities = Capabilities(false,false,32000,false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                    ModelReply(listOf(Part.Text("""[{"id":"p","action":"sign","why":"I support open discussion.","model":"invented"}]""")),StopReason.EndTurn,null)
            }
            var reported = ""
            PostReviewBoundary.review(engine,1000,true,hybrid,false,{emptyList()},{emptyList()},
                {model},{model},cosign = { choice, p, name ->
                    assertEquals("sign",choice.action)
                    assertEquals("p",p.id)
                    reported = name
                })
            assertEquals(model.name,reported)
        }
    }
    @Test fun communityAndDisabledNeverReadPrivateMemoryOrConstructModels() = runBlocking {
        val engine = PostStandby(save = {})
        engine.enqueue(listOf(post()), "mine", 1000)
        for ((enabled, community) in listOf(true to true, false to false)) {
            PostReviewBoundary.review(engine, 1000, enabled, false, community,
                { error("private memory") }, { error("feedback") },
                { error("own model") }, { error("hybrid model") })
        }
    }

    @Test fun ownAndHybridUseOnlyOwnersToolFreeModel() = runBlocking {
        for (hybrid in listOf(false, true)) {
            var calls = 0
            val engine = PostStandby(save = {})
            val post = post()
            engine.enqueue(listOf(post), "mine", 1000)
            val model = object : ChatModel {
                override val capabilities = Capabilities(false, false, 32000, false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    calls++
                    assertTrue(tools.isEmpty())
                    val prompt = (messages.single().parts.single() as Part.Text).text
                    assertTrue(prompt.contains("家里有小孩，想要二手婴儿车"))
                    assertTrue(prompt.contains("喜欢这一类：实用"))
                    assertTrue(prompt.contains(post.untrusted()))
                    return ModelReply(listOf(Part.Text("""[{"id":"p","action":"tell","why":"家里需要婴儿车"}]""")), StopReason.EndTurn, null)
                }
            }
            PostReviewBoundary.review(engine, 1000, true, hybrid, hybrid,
                { listOf("家里有小孩，想要二手婴儿车") }, { listOf("喜欢这一类：实用") },
                { assertFalse(hybrid); model }, { assertTrue(hybrid); model })
            assertEquals(1, calls)
            assertEquals("家里需要婴儿车", engine.takeReminder(1000, true)!!.second.single().why)
        }
    }

    @Test fun communityUsesPublicOnlyPromptAndModelNameAfterAssignment() = runBlocking {
        val engine = PostStandby(save = {})
        val public = post().copy(cosign = true, body = "PUBLIC_COSIGN_BODY")
        engine.enqueue(listOf(public,post().copy(id = "ordinary")),"mine",1000)
        var assigned = ""
        var closed = false
        var submitted = ""
        val model = object : ChatModel {
            override val name get() = assigned
            override val capabilities = Capabilities(false,false,32000,false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val prompt = (messages.single().parts.single() as Part.Text).text
                assertTrue(tools.isEmpty())
                assertTrue(prompt.contains("PUBLIC_COSIGN_BODY"))
                for (private in listOf(public.title,public.number,"ordinary","PRIVATE_OWNER","About the owner"))
                    assertFalse(prompt.contains(private))
                assigned = "Descriptor model from assigned node"
                return ModelReply(listOf(Part.Text("""[{"id":"p","action":"sign","why":"My independent reason","model":"invented"}]""")),StopReason.EndTurn,null)
            }
        }
        PostReviewBoundary.review(engine,1000,true,false,true,
            { error("PRIVATE_OWNER") },{ error("PRIVATE_FEEDBACK") },{ error("own") },{ error("hybrid") },
            cosign = { _, _, name -> submitted = name },remote = { model },
            closeRemote = { closed = true })
        assertEquals("Descriptor model from assigned node",submitted)
        assertTrue(closed)
    }

    @Test fun withdrawalHasDedicatedResultAndRetryCopy() {
        val source = java.io.File("src/main/java/com/wangzan101/mika/number/PostCardsActivity.kt").readText()
        assertTrue(source.contains("if (!positionPage)"))
        assertTrue(source.contains("if (positionPage && withdrawPosition == null)"))
        assertTrue(source.contains("withdrawPosition?.let { withdrawCosign(it) } else load()"))
        assertTrue(source.contains("onClick = { scope.launch { withdrawCosign(id) } }"))
        assertTrue(source.contains("R.string.post_cosign_withdraw_network"))
        for (language in listOf("values","values-zh","values-b+zh+Hant")) {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(java.io.File("src/main/res/$language/strings.xml"))
            val entries = doc.getElementsByTagName("string")
            val values = (0 until entries.length).associate { i ->
                val element = entries.item(i)
                element.attributes.getNamedItem("name").nodeValue to element.textContent
            }
            for (name in listOf("post_cosign_withdrawn","post_cosign_withdraw_network",
                    "post_cosign_withdraw_failed","post_cosign_withdraw_rejected","post_cosign_withdraw_retry")) {
                assertTrue(values.getValue(name).isNotBlank())
                assertFalse(values.getValue(name).contains("???"))
            }
            if (language == "values-zh") assertEquals("已撤回",values["post_cosign_withdrawn"])
        }
    }

    private fun post() = Post("p", "12345678", "九成新婴儿车", "80金币", coins = 80,
        created = 1000, expires = 99999999)
}
