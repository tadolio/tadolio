// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.model

import java.time.LocalDate

enum class KeyFailure { invalid, exhausted, rate_limited, other }

/** Only credential failures may advance to another key. Never inspect a successful response. */
fun keyFailure(status: Int, body: String): KeyFailure {
    if (status in 200..299 || status >= 500) return KeyFailure.other
    val text = body.lowercase()
    return when {
        status == 401 || status == 403 -> KeyFailure.invalid
        status == 402 || "insufficient_quota" in text || "insufficient_balance" in text || "insufficient balance" in text || "credit balance is too low" in text || "余额不足" in text -> KeyFailure.exhausted
        status == 429 -> KeyFailure.rate_limited
        "invalid api key" in text || "invalid_api_key" in text -> KeyFailure.invalid
        else -> KeyFailure.other
    }
}

fun keyFailure(error: ModelError): KeyFailure = when (error) {
    is ModelError.Auth -> KeyFailure.invalid
    is ModelError.QuotaExceeded -> KeyFailure.exhausted
    is ModelError.RateLimited -> KeyFailure.rate_limited
    else -> KeyFailure.other
}

/** Share this registry across models and requests. IDs must be opaque credential fingerprints. */
class KeyAvailability(
    private val now: () -> Long = System::currentTimeMillis,
    private val day: () -> String = { LocalDate.now().toString() },
    initial: Map<String, State> = emptyMap(),
    private val changed: (Map<String, State>) -> Unit = {},
) {
    data class State(val failure: KeyFailure, val date: String, val retryAt: Long)
    private val states = initial.toMutableMap()

    @Synchronized fun status(id: String): KeyFailure {
        val state = states[id] ?: return KeyFailure.other
        return if (state.failure == KeyFailure.rate_limited) {
            if (now() < state.retryAt) state.failure else KeyFailure.other
        } else if (state.date == day()) state.failure else KeyFailure.other
    }

    @Synchronized fun fail(id: String, failure: KeyFailure) {
        if (failure == KeyFailure.other) return
        states[id] = State(failure, day(), now() + 60_000)
        changed(states.toMap())
    }

    @Synchronized fun reset(ids: Collection<String>) {
        ids.forEach(states::remove)
        changed(states.toMap())
    }
}

/** Keeps streaming and per-attempt metering supplied by the underlying transports. */
class KeyFailoverModel(
    private val models: List<Pair<String, ChatModel>>,
    private val availability: KeyAvailability,
    private val fallback: ChatModel? = null,
    private val onFallback: () -> Unit = {},
    private val rotate: Boolean = false,
) : StreamingChatModel {
    init { require(models.isNotEmpty()) }
    override val name get() = models.first().second.name
    override val capabilities get() = models.first().second.capabilities
    private var notified = false
    private val turn = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        attempt { it.complete(system, messages, tools) }

    override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply =
        attempt { model ->
            if (model is StreamingChatModel) model.completeStreaming(system, messages, tools, onText)
            else model.complete(system, messages, tools)
        }

    private suspend fun attempt(call: suspend (ChatModel) -> ModelReply): ModelReply {
        var last: ModelError? = null
        val start = if (rotate) (turn.getAndIncrement() and Int.MAX_VALUE) % models.size else 0
        for (offset in models.indices) {
            val (id, model) = models[(start + offset) % models.size]
            if (availability.status(id) != KeyFailure.other) continue
            try { return call(model) } catch (error: ModelError) {
                val failure = keyFailure(error)
                if (failure == KeyFailure.other) throw error
                availability.fail(id, failure)
                last = error
            }
        }
        if (fallback != null) {
            synchronized(this) { if (!notified) { notified = true; onFallback() } }
            return call(fallback)
        }
        throw last ?: when (availability.status(models.first().first)) {
            KeyFailure.exhausted -> ModelError.QuotaExceeded()
            KeyFailure.rate_limited -> ModelError.RateLimited(60_000)
            else -> ModelError.Auth()
        }
    }
}
