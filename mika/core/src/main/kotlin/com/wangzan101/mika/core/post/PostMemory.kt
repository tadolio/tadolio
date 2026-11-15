// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import com.wangzan101.mika.core.memory.*

/** Uses the owner's existing encrypted memory store, including later edits and deletions. */
class PostMemory(private val store: MemoryStore) {
    fun preferences(): List<String> = block(items().filter { it.source.channel != CHANNEL })

    fun feedback(): List<String> = block(items().filter { it.source.channel == CHANNEL })

    /** Negative preference records stay on the phone. Keywords are supplied by the memory tool. */
    fun skipCommunity(post: Post): Boolean {
        val publicText = (post.title + "\n" + post.body + "\n" + post.tags.joinToString(" ")).lowercase()
        return items().filter { it.kind == MemoryKind.preference }.any { item ->
            val negative = Regex("别管|不再管|不要.*(推荐|关注|联署)|不喜欢这一类|leave.*alone|stop.*(suggest|cosign)|do not.*(suggest|cosign)",
                RegexOption.IGNORE_CASE).containsMatchIn(item.content)
            // An opt-out without topic keywords is conservatively applied to all community reviews.
            negative && (item.keywords.isEmpty() || item.keywords.any { it.lowercase() in publicText })
        }
    }

    private fun items(): List<MemoryItem> {
        check(store.reloadIfNeeded()) { "post_memory_unavailable" }
        // Facts contain household circumstances and needs. Approved procedures are not preferences.
        return store.items.value.filter { it.status == MemoryStatus.active && it.kind != MemoryKind.procedure }
    }

    private fun block(items: List<MemoryItem>): List<String> = listOfNotNull(
        MemoryContext.build(items, "", null, mode = MemoryContext.Mode.Full).message)

    /** Called only for an explicit card button press. Store rejection is surfaced to the UI. */
    fun record(suggestion: PostSuggestion, liked: Boolean): Result<Change.Added> = store.add(
        MemoryKind.preference, PostStandby.feedback(suggestion, liked), emptyList(),
        MemorySource(type = "manual", atMs = store.clock(), channel = CHANNEL),
    )

    companion object {
        private const val CHANNEL = "hivo_post_feedback"
    }
}
