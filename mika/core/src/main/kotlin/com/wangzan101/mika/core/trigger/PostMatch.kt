// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.trigger

import com.wangzan101.mika.core.post.Post
import kotlinx.serialization.Serializable

@Serializable
data class PostMatch(val keywords: List<String>, val maxCoins: Int? = null, val region: String? = null) {
    init {
        require(keywords.isNotEmpty() && keywords.all { it.isNotBlank() })
        require(maxCoins == null || maxCoins >= 0)
    }
}

/** Missing prices cannot satisfy a price ceiling. Regions are free text. */
fun matchesPost(match: PostMatch, post: Post, ownNumber: String, connectedAtMs: Long, nowMs: Long): Boolean =
    post.number != ownNumber && post.created >= connectedAtMs && post.created <= nowMs &&
        post.expires > nowMs && !post.withdrawn &&
        match.keywords.any { word -> (listOf(post.title, post.body) + post.tags).any { it.contains(word, ignoreCase = true) } } &&
        (match.maxCoins == null || post.coins?.let { it <= match.maxCoins } == true) &&
        (match.region.isNullOrEmpty() || post.region?.contains(match.region, ignoreCase = true) == true)
