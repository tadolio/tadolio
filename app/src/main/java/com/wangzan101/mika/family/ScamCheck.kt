// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import com.wangzan101.mika.core.access.CommunitySensitive
import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

internal enum class ScamVerdict { Suspicious, Uncertain, Normal }
internal data class ScamResult(val verdict: ScamVerdict, val reason: String, val advice: String)
internal sealed interface ScamState {
    data object Ready : ScamState
    data object Checking : ScamState
    data class Result(val result: ScamResult) : ScamState
    data object Failed : ScamState
}
/** No agent, no tools, no screen access, no message persistence. Model output is untrusted. */
internal object ScamCheck {
    fun accepts(action: String?, mime: String?): Boolean = action == "android.intent.action.SEND" &&
        (mime == "text/plain" || mime?.startsWith("image/") == true)
    fun sanitize(text: String): String {
        require(text.isNotBlank() && text.length <= 20000)
        return CommunitySensitive.redact(text)
    }
    fun parse(text: String): ScamResult {
        val obj = Json.parseToJsonElement(text.trim()).jsonObject
        require(obj.keys == setOf("verdict", "reason", "advice"))
        val verdict = when (obj.getValue("verdict").jsonPrimitive.content) {
            "suspicious" -> ScamVerdict.Suspicious
            "uncertain" -> ScamVerdict.Uncertain
            "normal" -> ScamVerdict.Normal
            else -> error("invalid_verdict")
        }
        fun field(key: String): String = obj.getValue(key).jsonPrimitive.let {
            require(it.isString && it.content.isNotBlank() && it.content.length <= 300)
            CommunitySensitive.redact(it.content)
        }
        return ScamResult(verdict, field("reason"), field("advice"))
    }
    suspend fun run(model: ChatModel, text: String, system: String): ScamResult = withTimeout(60000) {
        val safe = sanitize(text)
        val reply = model.complete(system, listOf(Message(Role.User, listOf(Part.Text(safe)), Author.User)), emptyList())
        require(reply.parts.none { it is Part.ToolCall })
        parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
    }
    suspend fun state(model: ChatModel, text: String, system: String): ScamState = try {
        ScamState.Result(run(model, text, system))
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { ScamState.Failed }
    catch (e: CancellationException) { throw e } catch (_: Exception) { ScamState.Failed }
}
