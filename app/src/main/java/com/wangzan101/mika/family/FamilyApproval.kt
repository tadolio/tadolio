// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal data class FamilyRequest(val id: String, val pair: String, val digest: String, val category: String, val expires: Long)
internal data class FamilyDecision(val requestId: String, val pair: String, val digest: String, val approved: Boolean, val expires: Long)
internal interface FamilyTransport {
    suspend fun send(request: FamilyRequest)
    suspend fun decisions(): List<FamilyDecision>
}
internal class FamilyApproval(private val transport: FamilyTransport, private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend () -> Unit = { delay(2000) }, private val timeout: Long = 600000) {
    suspend fun approve(pair: String?, digest: String, category: String): Boolean? {
        if (pair == null) return null // Existing on-phone confirmation remains responsible.
        val request = FamilyRequest(UUID.randomUUID().toString(), pair, digest, category, now() + timeout)
        return try {
            withTimeoutOrNull(timeout) {
                transport.send(request)
                while (now() < request.expires) {
                    val answer = transport.decisions().firstOrNull {
                        it.requestId == request.id && it.pair == pair && it.digest == digest && it.expires == request.expires
                    }
                    if (answer != null) return@withTimeoutOrNull answer.approved && now() < answer.expires
                    pause()
                }
                false
            } ?: false
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }
}

/** The only policy-aware route to approval transport and operation notifications. */
internal class FamilySharing(
    private val policy: com.wangzan101.mika.core.action.FamilyPolicy,
    private val approval: FamilyApproval,
    private val pair: String?,
    private val enqueue: (String) -> Unit,
) {
    fun mode(category: String) = policy.mode(category)
    suspend fun authorize(digest: String, category: String): Boolean =
        mode(category) != com.wangzan101.mika.core.action.FamilyMode.Approve || approval.approve(pair, digest, category) != false
    fun completed(category: String) {
        if (pair != null && mode(category) == com.wangzan101.mika.core.action.FamilyMode.Notify) enqueue(category)
    }
}
