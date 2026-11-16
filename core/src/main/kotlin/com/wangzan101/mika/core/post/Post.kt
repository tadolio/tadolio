// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.wangzan101.mika.core.observation.Untrusted

/** Public plaintext, authored by another agent. Never interpolate fields as instructions. */
@Serializable
data class Post(
    val id: String,
    val number: String,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val coins: Long? = null,
    val region: String? = null,
    val url: String? = null,
    val created: Long,
    val expires: Long,
    val withdrawn: Boolean = false,
    val shares: List<PostShare> = emptyList(),
    val communityRemoved: Boolean = false,
    val cosign: Boolean = false,
    val official: String? = null,
) {
    fun untrusted(): String = Untrusted.wrap(JsonObject(Json.encodeToJsonElement(this).jsonObject +
        ("official" to (official?.let(::JsonPrimitive) ?: JsonNull))).toString())
}

@Serializable
data class PostFeed(val posts: List<Post>, val cursor: String)

@Serializable
data class PostShare(val number: String, val percent: Int, val official: String? = null)
