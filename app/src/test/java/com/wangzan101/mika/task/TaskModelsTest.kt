// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.settings.ProviderModels
import com.wangzan101.mika.family.ElderModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskModelsTest {
    private class Fake(private val reply: String = "ok", private val fail: Boolean = false) : ChatModel {
        override val capabilities = Capabilities(true, true, 32000, false)
        val requests = mutableListOf<List<Message>>()
        val systems = mutableListOf<String>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            requests += messages
            systems += system
            check(!fail) { "simulated model failure" }
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
    }
    private fun message(text: String) = listOf(Message(Role.User, listOf(Part.Text(text)), Author.User))

    @Test fun ownSelectionsKeepIdentityAndSameModelSkipsOnlyStepRouting() {
        val main = Fake()
        val light = Fake()
        val vision = Fake()
        val models = TaskModels(main, light, vision, distinctLight = false, community = false)
        assertSame(main, models.main)
        assertSame(light, models.light)
        assertSame(vision, models.vision)
        assertSame(light, models.auxiliary(light))
        assertNull(models.routedLight)
        assertNull(models.auxiliary(null))
        assertSame(light, TaskModels(main, light, null, true, false).routedLight)
    }

    @Test fun pureCommunityAuxiliaryStillRedactsAndSupportsTheStepRouter() = runTest {
        val main = Fake()
        val light = Fake()
        val models = TaskModels(main, light, null, distinctLight = true, community = true)
        assertSame(light, models.light)
        assertSame(light, models.routedLight)
        models.auxiliary(light)!!.complete("BASE", message("验证码 123456"), emptyList())
        assertEquals(1, light.requests.size)
        assertFalse(light.requests.single().toString().contains("123456"))
    }

    @Test fun stoppedBeforeSelectionHasARecordOnlySummary() = runTest {
        val summary = StopSummary(null, null).summarize("weather", emptyList(), "working")
        assertTrue(summary.isNotBlank())
    }

}
