// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable reservation shared by conversation and standby. An update never sends a second notice. */
class PostCosignNotices(private val load: () -> Set<String>, private val save: (Set<String>) -> Unit) {
    private val mutex = Mutex()
    suspend fun once(id: String, notify: () -> Unit) = mutex.withLock {
        val seen = load()
        if (id in seen) return@withLock
        save(seen + id)
        notify()
    }
}
