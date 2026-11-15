// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

/** A fresh connection drains to the current high-water mark without replaying history.
 * Cursor is saved before dispatch: interrupted deliveries may be dropped, never replayed.
 * The host serializes polls. A failed fetch begins a fresh connection on the next poll.
 */
class PostPolling(
    private val readCursor: () -> String?,
    private val saveCursor: (String) -> Unit,
    private val collect: suspend (List<Post>) -> Unit = {},
) {
    private var connected = false

    suspend fun poll(fetch: suspend (String) -> PostFeed, receive: (Post) -> Unit) {
        try {
            // Bound a busy feed's work per poll. Baseline draining continues on later polls.
            repeat(10) {
                val after = readCursor()?.takeIf { it.matches(Regex("[0-9]+")) } ?: "0"
                val page = fetch(after)
                require(page.cursor.matches(Regex("[0-9]+")))
                require(page.cursor.toBigInteger() >= after.toBigInteger())
                require(page.posts.size <= 100)
                if (page.posts.isNotEmpty()) check(page.cursor != after) { "post_cursor_stalled" }
                // Standby can examine unseen posts since the durable cursor, including reconnects.
                // Persist its queue first. Replaying after a failed cursor save is deduplicated there.
                collect(page.posts)
                saveCursor(page.cursor)
                if (connected) page.posts.forEach(receive)
                if (page.posts.size < 100) {
                    connected = true
                    return
                }
            }
        } catch (e: Exception) {
            connected = false
            throw e
        }
    }
}
