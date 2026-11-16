// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.number.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GovernanceReviewBoundaryTest {
    @Test fun communityAndDisabledConstructNoModel() = runBlocking {
        for ((enabled, community) in listOf(true to true, false to false)) {
            GovernanceReviewBoundary.review(GovernanceJury(save = {}), cases, 0, enabled,
                community, { error("own") }, { _, _ -> error("vote") })
        }
    }

    @Test fun ownHasNoToolsAndUsesPrivateModel() = runBlocking {
        repeat(1) {
            var votes = 0
            val model = object : ChatModel {
                override val capabilities = Capabilities(false, false, 32000, false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    assertTrue(tools.isEmpty())
                    val prompt = (messages.single().parts.single() as Part.Text).text
                    assertTrue(prompt.contains("<untrusted>\ntarget"))
                    assertTrue(prompt.contains("<untrusted>\nreport"))
                    return ModelReply(listOf(Part.Text("""{"vote":"dismiss","reason":"uncertain"}""")), StopReason.EndTurn, null)
                }
            }
            GovernanceReviewBoundary.review(GovernanceJury(save = {}), cases, 0, true,
                false, { model },
                { _, ballot -> votes++; assertEquals("dismiss", ballot.vote) })
            assertEquals(1, votes)
        }
    }

    private val cases = listOf(JuryCase("a", "target", listOf("report")))
}
