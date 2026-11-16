// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Public-post transport. Approval belongs to the tool bridge before publish is called. */
interface PostPort {
    suspend fun publish(draft: PostDraft): String
    suspend fun withdraw(id: String)
    suspend fun search(q: String = "", tag: String? = null, number: String? = null, limit: Int = 100): List<Post>
    suspend fun get(id: String): Post
    suspend fun mine(): List<Post>
    suspend fun cosign(id: String, stance: String, reason: String, model: String) { error("cosign_unavailable") }
    suspend fun withdrawCosign(id: String) { error("cosign_unavailable") }
}

@Serializable
data class PostDraft(
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val coins: Long? = null,
    val region: String? = null,
    val url: String? = null,
    val expires: Long? = null,
    val shares: List<PostShare> = emptyList(),
    val cosign: Boolean = false,
)

/** Uses the existing signed device request, including for owner-number lookup. */
class HubPostPort(private val call: suspend (String, JsonObject?) -> JsonElement) : PostPort {
    private val json = Json { ignoreUnknownKeys = true }
    private fun encoded(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun id(value: String): String {
        require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() })
        return encoded(value)
    }

    override suspend fun publish(draft: PostDraft): String {
        // Omit optional null fields: the hub distinguishes absence from invalid explicit nulls.
        val outgoing = draft.copy(shares = draft.shares.map { it.copy(official = null) })
        val body = Json.encodeToJsonElement(outgoing).jsonObject.filterValues { it != JsonNull }
        return call("/v1/post", JsonObject(body)).jsonObject.getValue("id").jsonPrimitive.content
    }

    override suspend fun withdraw(id: String) {
        call("/v1/post/${id(id)}/withdraw", buildJsonObject {})
    }

    override suspend fun cosign(id: String, stance: String, reason: String, model: String) {
        require(stance in setOf("sign", "decline"))
        require(reason.isNotBlank() && reason.codePointCount(0, reason.length) <= 200)
        require(model.isNotBlank() && model.codePointCount(0, model.length) <= 60)
        call("/v1/post/${id(id)}/cosign", buildJsonObject {
            put("stance", stance); put("reason", reason); put("model", model)
        })
    }

    override suspend fun withdrawCosign(id: String) {
        call("/v1/post/${id(id)}/cosign/withdraw", buildJsonObject {})
    }

    override suspend fun search(q: String, tag: String?, number: String?, limit: Int): List<Post> {
        require(limit in 1..100)
        val query = buildList {
            add("q=${encoded(q)}")
            tag?.let { add("tag=${encoded(it)}") }
            number?.let { add("number=${encoded(it)}") }
            add("limit=$limit")
        }.joinToString("&")
        val posts = call("/v1/post/search?$query", null).jsonObject.getValue("posts")
        return json.decodeFromJsonElement(posts)
    }

    override suspend fun get(id: String): Post = json.decodeFromJsonElement(call("/v1/post/${id(id)}", null))

    override suspend fun mine(): List<Post> {
        val owner = call("/v1/number/mine", null)
        if (owner == JsonNull) return emptyList()
        val number = owner.jsonObject.getValue("number").jsonPrimitive.content
        require(Regex("[0-9]{8}").matches(number))
        return json.decodeFromJsonElement(call("/v1/post/mine", null).jsonObject.getValue("posts"))
    }
}
