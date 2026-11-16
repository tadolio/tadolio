// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.access.CommunityModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class T42AttachmentContentTest {
    private val image = AttachmentLines.composeBytes("Read this", listOf("/workspace/inbox/photo.jpg" to 3L))
    @Test fun actualImageBytesReachModelThroughCommunityBoundary() = runBlocking {
        var received = emptyList<Message>()
        val model = object : ChatModel {
            override val name = "test"
            override val capabilities = Capabilities(true, true, 32000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                received = messages
                return ModelReply(listOf(Part.Text("read")), StopReason.EndTurn, null)
            }
        }
        val parts = AttachmentContent.parts(image, true, { byteArrayOf(1, 2, 3) })
        CommunityModel(model).complete("", listOf(Message(Role.User, listOf(Part.Text(image)) + parts, Author.User)), emptyList())
        val photo = received.single().parts.filterIsInstance<Part.Image>().single()
        assertEquals("AQID", photo.pngBase64)
        assertTrue(photo.userAttachment)
    }
    @Test fun unsupportedImageFailsBeforeAnyFileRead() = runBlocking {
        var reads = 0
        val error = runCatching { AttachmentContent.parts(image, false, { reads++; byteArrayOf() }) }.exceptionOrNull()
        assertEquals("attachment_model_cannot_see", error?.message)
        assertEquals(0, reads)
    }
    @Test fun ordinaryFilesIncludeTheirContentsAndBinaryIsExplicit() = runBlocking {
        val text = AttachmentLines.composeBytes("", listOf("/workspace/inbox/list.csv" to 5L, "/workspace/inbox/data.zip" to 2L))
        val parts = AttachmentContent.parts(text, false, { "a,b,c".toByteArray() }).filterIsInstance<Part.Text>()
        assertTrue(parts[0].text.contains("a,b,c"))
        assertTrue(parts[1].text.contains("have not been read"))
    }
    @Test fun normalAgentEntryIncludesImageInTheActualRequest() = runBlocking {
        var seen = emptyList<Message>()
        val model = object : ChatModel {
            override val name = "test"
            override val capabilities = Capabilities(true, true, 32000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                seen = messages
                return ModelReply(listOf(Part.Text("The photo is visible.")), StopReason.EndTurn, null)
            }
        }
        val loop = AgentLoop(model, com.wangzan101.mika.core.assembly.toolDispatcher(com.wangzan101.mika.core.tool.FakeScreen()), "BASE",
            reviewFinalAnswer = false, attachmentContent = { AttachmentContent.parts(it, true, { byteArrayOf(1, 2, 3) }) })
        loop.run(image)
        assertTrue(seen.any { message -> message.parts.any { it is Part.Image && it.userAttachment } })
    }
    @Test fun screenImagesReachCommunityModels() = runBlocking {
        var seen = emptyList<Message>()
        val model = object : ChatModel {
            override val name = "test"
            override val capabilities = Capabilities(true, true, 32000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                seen = messages
                return ModelReply(emptyList(), StopReason.EndTurn, null)
            }
        }
        CommunityModel(model).complete("", listOf(Message(Role.User, listOf(Part.Image("screen")), Author.User)), emptyList())
        assertEquals("screen", seen.single().parts.filterIsInstance<Part.Image>().single().pngBase64)
    }
}
