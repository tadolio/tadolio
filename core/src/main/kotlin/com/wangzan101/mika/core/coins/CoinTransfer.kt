// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.coins

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/** Persist intent before export, then persist the voucher before deposit. Retries keep both IDs. */
class CoinTransfer(private val read: () -> String?, private val write: (String?) -> Unit) {
    private val lock = Mutex()
    suspend fun poll(number: String, confirmed: suspend () -> Long,
        export: suspend (JsonObject) -> JsonObject, deposit: suspend (JsonObject) -> Unit) = lock.withLock {
        var state = read()?.let { Json.parseToJsonElement(it).jsonObject }
        if (state == null) {
            val amount = confirmed()
            if (amount <= 0) return@withLock
            state = buildJsonObject { put("id", UUID.randomUUID().toString()); put("number", number); put("amount", amount) }
            write(state.toString())
        }
        // A number change must not spend a voucher issued to the old wallet.
        val voucherNumber = (state["voucher"] as? JsonObject)?.get("number") ?: state["number"]
        if (voucherNumber?.jsonPrimitive?.content != number) return@withLock
        if (state["voucher"] == null) {
            val intent = state
            state = export(intent)
            val voucher = state.getValue("voucher").jsonObject
            require(listOf("id", "number", "amount").all { voucher[it] == intent[it] })
            write(state.toString())
        }
        deposit(state)
        write(null)
    }
}

/** Recent entry IDs, persisted before notification, collapse a poll into one quiet notification. */
class CoinIncoming(private val read: () -> Set<String>?, private val write: (Set<String>) -> Unit) {
    fun collect(entries: List<CoinEntry>): List<CoinEntry> {
        val seen = read()
        val ids = entries.map { it.id }.toSet()
        write(ids)
        if (seen == null) return emptyList()
        return entries.filter { it.id !in seen && (it.delta > 0 && it.kind in setOf("receive", "share") ||
            it.delta == 0L && it.kind == "receive" && it.status == "proposed") }
    }
}

/** Persist before notifying so repeated polls and process restarts stay quiet. */
class CoinEscrowReminders(private val read: () -> Set<String>, private val write: (Set<String>) -> Unit) {
    fun collect(entries: List<CoinEntry>, now: Long): List<CoinEntry> {
        val seen = read()
        val held = entries.filter { it.kind == "pay" && it.status == "active" && it.dispute == null }
        val due = held.flatMap { entry -> entry.milestones.filter { m ->
            m.status == "pending" && m.due != null && m.atDue != null &&
                "${entry.receipt}/${m.milestone}" !in seen && now >= m.due - 86400000L && now < m.due
        }.map { m -> entry.copy(id = "${entry.receipt}/${m.milestone}", releaseAt = m.due,
            title = m.what, milestones = listOf(m)) } }
        write(seen + due.map { it.id })
        return due
    }
}

object CoinEscrowGoals {
    fun release(receipt: String, localized: String, milestone: Int = 0): String = "$localized\nhivo_coins_release receipt=$receipt milestone=$milestone"
    fun dispute(receipt: String, localized: String): String = "$localized\nhivo_report kind=payment id=$receipt"

    private val HINT = Regex("^(hivo_coins_(release|refund|accept|decline|cancel) receipt=[a-f0-9-]{36}( milestone=\\d+)?" +
        "|hivo_report kind=payment id=[a-f0-9-]{36})$")

    /** The chat bubble shows only the localized line; the tool hint after it is for the agent. */
    fun visible(text: String): String {
        val lines = text.lines()
        if (lines.size < 2 || !HINT.matches(lines.last())) return text
        return lines.dropLast(1).joinToString("\n").ifBlank { text }
    }
}
