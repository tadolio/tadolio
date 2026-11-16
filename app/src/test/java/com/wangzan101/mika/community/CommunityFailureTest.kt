// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.core.model.*
import java.io.IOException
import java.nio.file.Files
import com.wangzan101.mika.settings.KeyCipher
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CommunityFailureTest {
    private val messages = listOf(Message(Role.User, listOf(Part.Text("hello")), Author.Mika))
    private val answer = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""

    @Test fun modelNameComesFromSessionMetadataAndIgnoresResponseModel() = runBlocking {
        val model = CommunityModel(openSession = { object : CommunitySession {
            override val platform = "local"
            override val model = "Descriptor assigned model"
            override fun complete(payload: String) = answer.dropLast(1) + ",\"model\":\"invented response model\"}"
            override fun close() { }
        } })
        try {
            assertEquals("",model.name)
            model.complete("",messages,emptyList())
            assertEquals("Descriptor assigned model",model.name)
        } finally { model.close() }
    }

    @Test fun uncheckedFailureClosesOldSessionAndRetriesWithNewSession() = runBlocking {
        val closed = CountDownLatch(1)
        var opens = 0
        val model = CommunityModel(openSession = {
            val attempt = ++opens
            object : CommunitySession {
                override val platform = "local"
                override fun complete(payload: String): String {
                    if (attempt == 1) throw IllegalStateException("community_session_closed")
                    return answer
                }
                override fun close() { if (attempt == 1) closed.countDown() }
            }
        })
        try {
            val failure = runCatching { withTimeout(5000) { model.complete("", messages, emptyList()) } }.exceptionOrNull()
            assertTrue(failure is ModelError.Network)
            val causes = generateSequence(failure) { it.cause }.take(32).toList()
            assertTrue(causes.any { it is IOException && it.message == "community_request_failed" })
            assertTrue(causes.any { it is IllegalStateException && it.message == "community_session_closed" })
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            model.complete("", messages, emptyList())
            assertEquals(2, opens)
        } finally { model.close() }
    }

    @Test fun unavailableNodeReachesNormalModelFailure() = runBlocking {
        val server = MockWebServer()
        server.start()
        val dir = Files.createTempDirectory("t19-client").toFile()
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray) = data
        }
        server.enqueue(MockResponse().setBody("""{"clientId":"test","token":"test"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"node_unavailable"}"""))
        val model = CommunityModel(CommunityClient(server.url("/").toString(), dir, cipher, true), "compute")
        try {
            val failure = runCatching { withTimeout(5000) { model.complete("", messages, emptyList()) } }.exceptionOrNull()
            assertTrue(failure is ModelError.Network)
            val causes = generateSequence(failure) { it.cause }.take(32).toList()
            assertTrue(causes.any { it is IOException && it.message == "community_request_failed" })
            assertTrue(causes.any { it is IOException && it.message == "community_hub_503" })
            assertEquals("/v1/client/register", server.takeRequest().path)
            assertEquals("/v1/session", server.takeRequest().path)
        } finally { model.close(); server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun cancellationStillClosesSession() = runBlocking {
        var closed = false
        var cleanupContinued = false
        val model = CommunityModel(openSession = { object : CommunitySession {
            override val platform = "local"
            override fun complete(payload: String) = answer
            override fun close() { closed = true }
        } })
        model.complete("", messages, emptyList())
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { model.close(); cleanupContinued = true }
        }
        job.cancelAndJoin()
        assertTrue(closed)
        assertTrue(cleanupContinued)
    }

    @Test fun encryptedBackendFailureClosesOverHttpAndNewSessionStartsAtOne() = runBlocking {
        val server = MockWebServer()
        val closed = CountDownLatch(1)
        val keys = ByteArray(32) { 1 } to ByteArray(32) { 2 }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path == "/v1/session/old/close") {
                    closed.countDown()
                    return MockResponse().setBody("{}")
                }
                if (path.endsWith("/close")) return MockResponse().setBody("{}")
                val id = path.substringAfterLast('/')
                val seq = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("seq").jsonPrimitive.int
                assertEquals(1, seq)
                val clear = if (id == "old") """{"ok":false,"error":"backend_failed"}"""
                    else """{"ok":true,"response":$answer}"""
                return MockResponse().setBody(buildJsonObject {
                    put("seq", seq)
                    put("ciphertext", CommunityCrypto.encrypt(keys.second, id, "n2c", seq, clear))
                }.toString())
            }
        }
        server.start()
        val dir = Files.createTempDirectory("t19-relay").toFile()
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray) = data
        }
        val client = CommunityClient(server.url("/").toString(), dir, cipher, true)
        var opens = 0
        val model = CommunityModel(openSession = {
            client.Session(if (++opens == 1) "old" else "new", "test", keys.first.copyOf() to keys.second.copyOf(), "node", "local")
        })
        try {
            assertTrue(runCatching { model.complete("", messages, emptyList()) }.exceptionOrNull() is ModelError.Network)
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            model.complete("", messages, emptyList())
            assertEquals(2, opens)
        } finally { model.close(); server.shutdown(); dir.deleteRecursively() }
    }
}
