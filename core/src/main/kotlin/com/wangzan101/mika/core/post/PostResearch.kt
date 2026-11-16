// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * A batch may open the source links that the posts themselves cite before producing its JSON decision array.
 * The post's stated events are its premise; there is no open web search, so agents never "fail to find" the news.
 */
class PostResearch(private val read: (suspend (String) -> String)? = null) {
    suspend fun complete(model: ChatModel, system: String, prompt: String): String {
        val sources = links(prompt)
        val messages = mutableListOf(Message(Role.User, listOf(Part.Text(prompt)), Author.Mika))
        var reads = 0
        // Three reading rounds plus one final JSON response, regardless of model compliance.
        repeat(4) { round ->
            val tools = if (round < 3 && reads < MAX_READS && read != null && sources.isNotEmpty()) listOf(READ) else emptyList()
            val reply = model.complete(system, messages, tools)
            val calls = reply.parts.filterIsInstance<Part.ToolCall>()
            if (calls.isEmpty()) return reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            messages += Message(Role.Assistant, reply.parts, Author.Mika)
            val results = calls.map { call ->
                var failed = false
                val result = try {
                    check(call.name == READ_NAME) { "unsupported_research_tool" }
                    // Charge attempts before parsing or I/O, including failed requests.
                    check(round < 3 && reads < MAX_READS) { "read_limit_reached" }
                    reads++
                    val url = Json.parseToJsonElement(call.argsJson).jsonObject.getValue("url").jsonPrimitive.content
                    check(url in sources) { "not_a_post_source" }
                    checkNotNull(read)(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                    "Source unavailable, not cited by the post, or limit reached. Decide with the post as written."
                }
                Part.ToolResult(call.id, Untrusted.wrap(result.take(16_000)), failed)
            }
            messages += Message(Role.Tool, results, Author.Mika)
            if (round == 2) messages += Message(Role.User,
                listOf(Part.Text("Reading budget finished. Return only the final JSON decision array now.")), Author.Mika)
        }
        error("post_review_no_json")
    }

    companion object {
        const val MAX_READS = 3
        private const val READ_NAME = "open_post_source"
        private val URL = Regex("""https?://[^\s<>"')\]]+""")
        /** Links written in the posts; trailing punctuation is not part of the address. */
        fun links(text: String): Set<String> = URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '。', '，') }.toSet()
        private val READ = ToolDef(READ_NAME, "Open a source link cited in a post's body to look at it.",
            mapOf("url" to str("One of the links written in the post body, exactly as written.")), listOf("url")).spec()
    }
}
