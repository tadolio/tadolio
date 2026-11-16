// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import com.wangzan101.mika.core.number.NumberPort
import java.util.UUID

internal class NumberAgentPort(private val client: NumberClient) : NumberPort {
    override suspend fun bindInviter(number: String) = client.bindInviter(number)
    override val governance get() = client.governance
    override val coins get() = client.coins
    override val posts get() = client.posts
    override suspend fun community(number: String) = client.lookup(number)["community"]?.let {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    } ?: false
    override fun contacted(number: String) = client.store.contacted(number)
    override fun inbox() = client.store.transactions().filter {
        it.state == "pending" && !client.blocked(it.number)
    }.map { NumberPort.Item(it.number, it.thread, it.subject, it.summary, it.state, it.text, it.official) }

    override suspend fun send(number: String, kind: String, subject: String, text: String, thread: String?) {
        require(!client.blocked(number)) { "number_blocked" }
        val transaction = thread?.let { id -> client.store.transactions().firstOrNull {
            it.number == number && it.thread == id
        } }
        client.send(number, NumberEnvelope(kind, subject, text, thread ?: UUID.randomUUID().toString(),
            transaction?.lastId), owner = false)
    }
}
