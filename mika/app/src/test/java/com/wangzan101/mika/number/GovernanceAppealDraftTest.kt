// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GovernanceAppealDraftTest {
    @Test fun draftWrapsBothInputsAndHasNoTools() = runBlocking {
        val model = object : ChatModel {
            override val capabilities = Capabilities(false, false, 32000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                assertTrue(tools.isEmpty())
                val prompt = (messages.single().parts.single() as Part.Text).text
                assertTrue(prompt.contains("<untrusted>\ncase"))
                assertTrue(prompt.contains("<untrusted>\nowner"))
                return ModelReply(listOf(Part.Text("  Please reconsider.  ")), StopReason.EndTurn, null)
            }
        }
        assertEquals("Please reconsider.", GovernanceAppealDraft.draft("case", "owner", model))
    }

    @Test fun rejectsEmptyAndOversizedDrafts() = runBlocking {
        for (text in listOf("", "x".repeat(501))) {
            val model = object : ChatModel {
                override val capabilities = Capabilities(false, false, 32000, false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                    ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, null)
            }
            try { GovernanceAppealDraft.draft("case", "owner", model); fail("invalid draft") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun notificationSelectsOnlyCaseIds() {
        val id = "8ce397e2-64c5-4bc1-ae92-26e25018eab3"
        assertEquals(id, GovernanceNavigation.caseId(id))
        for (value in listOf(null, "", "../../case", "https://example.com", "$id?other"))
            assertNull(GovernanceNavigation.caseId(value))
    }
}
