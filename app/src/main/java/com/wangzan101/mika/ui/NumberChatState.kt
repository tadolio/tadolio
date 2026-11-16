// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.family.str
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class NumberConversation(val number: String, val preview: String, val time: Long, val unread: Int)

internal object NumberChatState {
    fun validNumber(value: String) = value.matches(Regex("[0-9]{8}"))
    fun formatted(value: String, official: String? = null) =
        com.wangzan101.mika.core.number.OfficialNumber.display(value, official)
    fun outgoing(message: JsonObject) = message["outgoing"]?.jsonPrimitive?.booleanOrNull == true
    fun time(message: JsonObject) = message.getValue("time").jsonPrimitive.long
    fun peer(message: JsonObject) = message.str(if (outgoing(message)) "to" else "from")
    fun preview(text: String) = text.replace(Regex("\\s+"), " ").trim()
    fun summaries(messages: List<JsonObject>, seen: Set<String>): List<NumberConversation> =
        messages.groupBy(::peer).map { (number, items) ->
            val last = items.maxBy(::time)
            NumberConversation(number, preview(last.str("text")), time(last),
                items.count { !outgoing(it) && it.str("id") !in seen })
        }.sortedWith(compareByDescending<NumberConversation> { it.time }.thenBy { it.number })
    fun read(messages: List<JsonObject>, number: String, seen: Set<String>) =
        seen + messages.filter { peer(it) == number && !outgoing(it) }.map { it.str("id") }
    fun delivered(draft: JsonObject, messages: List<JsonObject>): Boolean {
        val id = draft["id"]?.jsonPrimitive?.content.orEmpty()
        val before = draft["before"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
        return messages.any {
            outgoing(it) && it.str("to") == draft.str("to") && it.str("text") == draft.str("text") &&
                (draft["thread"] == null || it["thread"] == draft["thread"]) &&
                if (id.isNotEmpty()) it.str("id") == id
                else it.str("id") !in before && time(it) >= time(draft)
        }
    }
    fun separator(previous: Long?, current: Long) = previous == null || current - previous > 300000L
    fun listTime(time: Long, now: Long, yesterday: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val date = Instant.ofEpochMilli(time).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date.toLocalDate()) {
            today -> date.format(DateTimeFormatter.ofPattern("HH:mm", locale))
            today.minusDays(1) -> yesterday
            else -> date.format(DateTimeFormatter.ofPattern("M/d", locale))
        }
    }
}
