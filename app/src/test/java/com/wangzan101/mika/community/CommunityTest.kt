// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommunityTest {
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private val cipher = object : KeyCipher { override fun encrypt(plain: ByteArray) = plain; override fun decrypt(data: ByteArray) = data }
    @Test fun roundTripAndTampering() {
        val a = CommunityCrypto.generate(); val b = CommunityCrypto.generate()
        val keys = CommunityCrypto.keys(a.private, b64(b.public.encoded), "test-session")
        val other = CommunityCrypto.keys(b.private, b64(a.public.encoded), "test-session")
        assertArrayEquals(keys.first, other.first); assertArrayEquals(keys.second, other.second)
        val encrypted = CommunityCrypto.encrypt(keys.first, "test-session", "c2n", 1, "private 中文")
        assertEquals("private 中文", CommunityCrypto.decrypt(other.first, "test-session", "c2n", 1, encrypted))
        for ((session, direction, seq) in listOf(Triple("other", "c2n", 1), Triple("test-session", "n2c", 1), Triple("test-session", "c2n", 2))) {
            assertThrows(Exception::class.java) { CommunityCrypto.decrypt(other.first, session, direction, seq, encrypted) }
        }
        assertThrows(Exception::class.java) { CommunityCrypto.encrypt(keys.first, "s", "c2n", 0, "x") }
    }
    @Test fun exportsJvmVectorAndReadsNodeVector() {
        val a = CommunityCrypto.generate(); val b = CommunityCrypto.generate(); val id = "jvm-node-vector-v1"
        val k = CommunityCrypto.keys(a.private, b64(b.public.encoded), id)
        val data = buildJsonObject {
            put("session", id); put("clientPrivate", b64(a.private.encoded)); put("clientPublic", b64(a.public.encoded))
            put("nodePrivate", b64(b.private.encoded)); put("nodePublic", b64(b.public.encoded))
            put("plain", "private 中文 tool_call"); put("ciphertext", CommunityCrypto.encrypt(k.first, id, "c2n", 1, "private 中文 tool_call"))
        }
        File("build/t16-jvm-vector.json").apply { parentFile.mkdirs(); writeText(data.toString()) }
        val nodeFile = File("build/t16-node-vector.json")
        if (nodeFile.isFile) {
            val n = Json.parseToJsonElement(nodeFile.readText()).jsonObject
            val secret = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(n.getValue("clientPrivate").jsonPrimitive.content)))
            val keys = CommunityCrypto.keys(secret, n.getValue("nodePublic").jsonPrimitive.content, id)
            assertEquals("node reply 中文", CommunityCrypto.decrypt(keys.second, id, "n2c", 1, n.getValue("ciphertext").jsonPrimitive.content))
            n["descriptor"]?.jsonObject?.let { descriptor ->
                val signature = n.getValue("signature").jsonPrimitive.content
                CommunityCrypto.verifyDescriptor(descriptor, signature)
                assertThrows(Exception::class.java) { CommunityCrypto.verifyDescriptor(JsonObject(descriptor + ("model" to JsonPrimitive("tampered"))), signature) }
            }
        }
    }
    @Test fun assignmentModelIsReadFromTheSignedDescriptor() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val dir = java.nio.file.Files.createTempDirectory("t44-assignment").toFile()
        try {
            val signing = CommunityCrypto.generate()
            val agreement = CommunityCrypto.generate()
            val descriptor = buildJsonObject {
                put("version",1); put("signingKey",b64(signing.public.encoded)); put("agreementKey",b64(agreement.public.encoded))
                put("kind","compute"); put("model","Assigned actual model"); put("maxConcurrent",1)
                put("platform","local"); put("permission","own_compute"); put("configuration","test deployment")
            }
            val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(signing.private)
                update(descriptor.toString().toByteArray(Charsets.UTF_8))
                CommunityCrypto.encode(sign())
            }
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"clientId":"test","token":"test"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(buildJsonObject {
                put("descriptor",descriptor); put("signature",signature); put("sessionId","assigned")
                put("sessionToken","test-session"); put("nodeId","node")
            }.toString()))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
            val session = CommunityClient(server.url("/").toString(),dir,cipher,true).open("compute")
            assertEquals("Assigned actual model",session.model)
            session.close()
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun reRegistersWhenHubForgetsClient() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val dir = File("build/t33-reregister").apply { deleteRecursively(); mkdirs() }
            val url = server.url("/").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"clientId":"old","token":"stale"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"nodeId":null}"""))
            CommunityClient(url, dir, cipher, true).binding()
            // The hub was reset: the cached identity is rejected once, then replaced.
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"clientId":"new","token":"fresh"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"nodeId":null}"""))
            val client = CommunityClient(url, dir, cipher, true)
            assertEquals(JsonNull, client.binding()["nodeId"])
            repeat(2) { server.takeRequest() }
            assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
            assertEquals("/v1/client/register", server.takeRequest().path)
            assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
            // A second 401 right after re-registering is reported, not looped.
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"clientId":"n2","token":"t2"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
            assertThrows(java.io.IOException::class.java) { client.binding() }
        } finally { server.shutdown() }
    }
    @Test fun urlPolicyAndRealHttpE2e() = runBlocking {
        val dir = File("build/t16-client").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) { CommunityClient("http://example.com", dir, cipher, true) }
        assertThrows(IllegalArgumentException::class.java) { CommunityClient("http://127.0.0.1", dir, cipher, false) }
        assertThrows(IllegalArgumentException::class.java) { CommunityClient("https://user:pass@example.com", dir, cipher, false) }
        val endpoint = System.getenv("HIVO_T16_HUB_URL") ?: return@runBlocking
        var platform: String? = null
        val model = CommunityModel(CommunityClient(endpoint, dir, cipher, true), "compute") { platform = it }
        try {
            val reply = model.complete("T16_PRIVATE_SENTINEL", listOf(Message(Role.User, listOf(Part.Text("Call get_time")), Author.Mika)), listOf(ToolSpec("get_time", "Time", """{"type":"object","properties":{}}""")))
            val tool = reply.parts.filterIsInstance<Part.ToolCall>().single()
            assertEquals("get_time", tool.name)
            assertEquals(if (System.getenv("HIVO_T32_CLOUD") == "1") "siliconflow" else "local", platform)
        } finally { model.close() }
    }
}
