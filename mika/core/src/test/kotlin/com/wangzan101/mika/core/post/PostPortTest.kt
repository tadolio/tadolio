// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.post

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PostPortTest {
    private val post = Post("id", "12345678", "title", "body", created = 1, expires = 2)

    @Test fun officialMetadataDecodesAndIsNeverPublishedByClients() = runBlocking {
        for (label in listOf(null, "Tadolio Official")) {
            val remote = post.copy(official = label, shares = listOf(PostShare("88888888", 10, label)))
            val port = HubPostPort { _, _ -> Json.encodeToJsonElement(remote) }
            assertEquals(remote, port.get("id"))
            val feed = Json.decodeFromJsonElement<PostFeed>(buildJsonObject {
                put("posts", Json.encodeToJsonElement(listOf(remote)))
                put("cursor", "1")
            })
            assertEquals(remote, feed.posts.single())
        }
        val port = HubPostPort { _, body ->
            assertFalse(body!!.toString().contains("official"))
            buildJsonObject { put("id", "new") }
        }
        assertEquals("new", port.publish(PostDraft("t", "b", shares = listOf(PostShare("88888888", 10, "Forged Official")))))
    }

    @Test fun publishPreservesPublicFieldsAndOmitsAbsentOptionals() = runBlocking {
        val port = HubPostPort { path, body ->
            assertEquals("/v1/post", path)
            assertEquals(setOf("title", "body"), body!!.keys)
            assertEquals("title", body.getValue("title").jsonPrimitive.content)
            buildJsonObject { put("id", "new") }
        }
        assertEquals("new", port.publish(PostDraft("title", "body")))
        val full = PostDraft("t", "b", listOf("free tag"), 0, "region", "https://example.org", 20)
        val fullPort = HubPostPort { _, body ->
            assertEquals(Json.encodeToJsonElement(full), body)
            buildJsonObject { put("id", "full") }
        }
        assertEquals("full", fullPort.publish(full))
    }

    @Test fun searchEncodesFiltersAndDecodesPosts() = runBlocking {
        val port = HubPostPort { path, body ->
            assertEquals("/v1/post/search?q=a%26number%3Dother&tag=%E9%9D%A2%E5%8C%85&number=12345678&limit=50", path)
            assertNull(body)
            buildJsonObject { put("posts", Json.encodeToJsonElement(listOf(post))) }
        }
        assertEquals(listOf(post), port.search("a&number=other", "面包", "12345678", 50))
    }

    @Test fun mineUsesAuthenticatedNumberAndHandlesUnclaimedDevice() = runBlocking {
        val paths = mutableListOf<String>()
        val port = HubPostPort { path, _ ->
            paths.add(path)
            if (path == "/v1/number/mine") buildJsonObject { put("number", "12345678") }
            else buildJsonObject { put("posts", Json.encodeToJsonElement(listOf(post))) }
        }
        assertEquals(listOf(post), port.mine())
        assertEquals(listOf("/v1/number/mine", "/v1/post/mine"), paths)
        assertTrue(HubPostPort { _, _ -> JsonNull }.mine().isEmpty())
    }

    @Test fun minePreservesCommunityRemovalAndOlderPostsDefaultToVisible() = runBlocking {
        val removed = post.copy(communityRemoved = true)
        val port = HubPostPort { path, _ ->
            if (path == "/v1/number/mine") buildJsonObject { put("number", "12345678") }
            else buildJsonObject { put("posts", Json.encodeToJsonElement(listOf(post, removed))) }
        }
        val mine = port.mine()
        assertFalse(mine[0].communityRemoved)
        assertTrue(mine[1].communityRemoved)
        assertTrue(mine[1].untrusted().contains("communityRemoved"))
    }

    @Test fun getAndWithdrawKeepIdsInsideOnePathSegment() = runBlocking {
        val paths = mutableListOf<String>()
        val port = HubPostPort { path, body ->
            paths.add(path)
            if (body == null) Json.encodeToJsonElement(post)
            else { assertEquals(buildJsonObject {}, body); buildJsonObject { put("ok", true) } }
        }
        assertEquals(post, port.get("a/b?c"))
        port.withdraw("a/b?c")
        assertEquals(listOf("/v1/post/a%2Fb%3Fc", "/v1/post/a%2Fb%3Fc/withdraw"), paths)
    }

    @Test fun invalidLimitsAndIdsNeverReachTransport() = runBlocking {
        val port = HubPostPort { _, _ -> error("unexpected request") }
        for (limit in listOf(0, 101)) {
            try { port.search(limit = limit); fail() } catch (_: IllegalArgumentException) { }
        }
        for (id in listOf("", "\n", "a".repeat(129))) {
            try { port.get(id); fail() } catch (_: IllegalArgumentException) { }
            try { port.withdraw(id); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
