// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Silent, serialized startup work with durable exponential failure backoff. */
internal class NumberProvisioning(
    private val nextAttempt: () -> Long,
    private val failures: () -> Int,
    private val save: (Long, Int) -> Unit,
    private val ensureNumber: suspend () -> Unit,
    private val linkCompute: suspend () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var refreshedAt: Long? = null

    suspend fun refresh() = lock.withLock {
        val time = now()
        if (time < nextAttempt() || refreshedAt?.let { time - it < 3600000L } == true) return@withLock
        try {
            ensureNumber()
            linkCompute()
            save(0, 0)
            refreshedAt = time
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val count = (failures() + 1).coerceIn(1, 10)
            save(time + (60000L shl (count - 1)).coerceAtMost(6 * 3600000L), count)
        }
    }
}
