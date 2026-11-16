// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GovernanceEffect(
    val case: JsonObject,
    val until: Long?,
    val reversed: Boolean,
    val active: Boolean,
    val appeal: JsonObject?,
    val appealUntil: Long,
    val canAppeal: Boolean,
)

@Serializable
data class GovernanceEffectsState(
    val effects: List<GovernanceEffect> = emptyList(),
    val notified: Set<String> = emptySet(),
)

/** Persist the latest owner view and reserve each case notification before delivery. */
class GovernanceEffects(
    initial: GovernanceEffectsState = GovernanceEffectsState(),
    private val save: (GovernanceEffectsState) -> Unit,
) {
    private val mutex = Mutex()
    private var state = initial

    suspend fun collect(effects: List<GovernanceEffect>): List<GovernanceEffect> = mutex.withLock {
        val fresh = effects.filter { it.active && it.case.getValue("id").jsonPrimitive.content !in state.notified }
            .distinctBy { it.case.getValue("id") }
        val next = state.copy(effects = effects,
            notified = state.notified + fresh.map { it.case.getValue("id").jsonPrimitive.content })
        save(next)
        state = next
        fresh
    }
}
