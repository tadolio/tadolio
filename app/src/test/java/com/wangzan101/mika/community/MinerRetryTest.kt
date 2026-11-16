// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MinerRetryTest {
    @Test fun retryUsesExistingIdentityAndPersistsQueueAndRateLimitMetadata() {
        val server=MockWebServer()
        server.start()
        val file=File.createTempFile("t63-miner", ".encrypted").apply { delete() }
        val prefs=MemoryPrefs()
        val cipher=object:KeyCipher {
            override fun encrypt(plain:ByteArray)=plain
            override fun decrypt(data:ByteArray)=data
        }
        val store=MinerStore(file,cipher,prefs)
        try {
            store.update { buildJsonObject { put("token","offline-test-token");put("model","fixture") } }
            val worker=MinerWorker(null,store,server.url("/").toString()){true}
            server.enqueue(MockResponse().setBody("""{"ok":true,"examStatus":{"status":"waiting_exam","retryRemaining":2,"retryAllowed":false}}"""))
            worker.retryExam()
            val request=server.takeRequest()
            assertEquals("/v1/node/exam/retry",request.path)
            assertEquals("Bearer offline-test-token",request.getHeader("Authorization"))
            assertEquals("{}",request.body.readUtf8())
            assertEquals(MinerState.WAITING_EXAM,store.phase)
            assertEquals(2,MinerStore(file,cipher,prefs).examStatus["retryRemaining"]!!.jsonPrimitive.int)
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"exam_retry_limit","examStatus":{"status":"failed","retryAllowed":true,"retryRemaining":0,"retryResetAt":1234}}"""))
            worker.retryExam()
            assertEquals(0,store.examStatus["retryRemaining"]!!.jsonPrimitive.int)
            assertEquals(1234L,store.examStatus["retryResetAt"]!!.jsonPrimitive.long)
            assertEquals("fixture",store.read()["model"]!!.jsonPrimitive.content)
        } finally { server.shutdown();file.delete() }
    }
}
