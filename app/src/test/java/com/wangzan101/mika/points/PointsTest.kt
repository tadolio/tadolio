// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.points

import com.wangzan101.mika.core.agent.*
import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PointsTest {
    private val report = PointsReport("00000000-0000-4000-8000-000000000001", 1793591390878, 1793591470000,
        "done", "success", 14, "byok", "0.1.34")

    @Test fun exactSigningInputWithQueryEmptyBodyAndNoFinalNewline() {
        val input = PointsSigning.input("GET", "/v1/points?a=1%202&b=3", "1793591470000", "nonce", "")
        assertEquals("GET\n/v1/points?a=1%202&b=3\n1793591470000\nnonce\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", input)
        assertFalse(input.endsWith("\n"))
        assertEquals(5, input.split("\n").size)
    }
    @Test fun utf8BodyIsHashedExactlyWithoutReserialization() {
        val body = "{ \"text\": \"\u4e2d\u6587\" }"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        val expected = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, PointsSigning.input("POST", "/v1/tasks", "1", "n", body).substringAfterLast('\n'))
        assertNotEquals(PointsSigning.input("POST", "/v1/tasks", "1", "n", body),
            PointsSigning.input("POST", "/v1/tasks", "1", "n", "{\"text\":\"\u4e2d\u6587\"}"))
    }
    @Test fun privacyExactEightKeys() {
        assertEquals(setOf("taskId", "startedAt", "endedAt", "outcome", "judge", "steps", "computeSource", "appVersion"),
            Json.parseToJsonElement(report.body()).jsonObject.keys)
    }
    @Test fun everyTerminalAndJudgeMapping() {
        assertEquals("done", PointsMapping.outcome(TaskState.Succeeded("answer")))
        assertEquals("cancelled", PointsMapping.outcome(TaskState.Cancelled))
        FailKind.entries.forEach { kind -> assertEquals(if (kind == FailKind.GaveUp) "abandoned" else "failed",
            PointsMapping.outcome(TaskState.Failed("private reason", kind))) }
        assertEquals(listOf("success", "partial", "fail", "none"), RunOutcome.entries.map { PointsMapping.judge(it) })
        assertEquals("none", PointsMapping.judge(null))
    }
    @Test(expected = IllegalStateException::class) fun unfinishedCannotBeReported() { PointsMapping.outcome(TaskState.Idle) }
    @Test fun everyProviderMapping() {
        ProviderConfig.Kind.entries.forEach { kind ->
            assertEquals(when (kind) { ProviderConfig.Kind.Community -> "community"; ProviderConfig.Kind.ChatGpt -> "chatgpt"; ProviderConfig.Kind.Xai -> "xai"; else -> "byok" }, PointsMapping.source(kind))
        }
    }
    private class Disk { var text = "[]"; fun open() = PointsOutbox({ text }, { text = it }) }
    @Test fun successAndExplicit4xxRemoveDurably() {
        listOf(200, 201, 204, 400, 403, 404, 409, 413, 422).forEach { code ->
            val disk = Disk(); val queue = disk.open(); queue.enqueue(report)
            queue.settle(queue.entries.single(), code, 1000)
            assertTrue("$code", disk.open().entries.isEmpty())
        }
    }
    @Test fun network429And5xxPersistWithBackoffAndSameIdAndBody() {
        listOf(null, 401, 408, 425, 429, 500, 502, 503).forEach { code ->
            val disk = Disk(); var queue = disk.open(); queue.enqueue(report)
            queue.settle(queue.entries.single(), code, 1000)
            queue = disk.open()
            val first = queue.entries.single()
            assertEquals(31_000L, first.nextAt); assertEquals(1, first.attempts)
            assertEquals(report.body(), first.report.body())
            queue.settle(first, code, 31_000)
            assertEquals(91_000L, disk.open().entries.single().nextAt)
        }
    }
    @Test fun retryAfterAndCappedBackoff() {
        val first = PointsRetry.after(PointsPending(report), 429, 1000, 120_000)!!
        assertEquals(121_000L, first.nextAt)
        assertEquals(3_601_000L, PointsRetry.after(first.copy(attempts = 90), 503, 1000)!!.nextAt)
    }
    @Test fun duplicateIsNotReplacedAndOptOutClearsPersistedQueue() {
        val disk = Disk(); val queue = disk.open(); queue.enqueue(report)
        queue.enqueue(report.copy(steps = 99))
        assertEquals(listOf(PointsPending(report)), disk.open().entries)
        queue.clear(); assertTrue(disk.open().entries.isEmpty())
    }
    @Test fun diskWriteFailureDoesNotLoseQueuedRecord() {
        var fail = false; var saved = "[]"
        val queue = PointsOutbox({ saved }, { if (fail) error("disk full") else saved = it })
        queue.enqueue(report); fail = true
        assertTrue(runCatching { queue.settle(queue.entries.single(), 200, 1) }.isFailure)
        assertEquals(report, queue.entries.single().report)
        assertEquals(report, PointsOutbox({ saved }, {}).entries.single().report)
    }
    @Test fun jvmP256SignatureAndExportServerVectors() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
        assertEquals(0, publicKey.length % 4)
        val vectors = listOf(Triple("POST", "/v1/tasks", report.body()),
            Triple("POST", "/v1/tasks?x=%E4%B8%AD", "{ \"text\": \"\u4e2d\u6587\" }"), Triple("GET", "/v1/points", ""))
        val result = buildJsonArray {
            vectors.forEach { (method, path, body) ->
                val timestamp = "1793591470000"; val nonce = PointsSigning.nonce()
                assertFalse(nonce.contains('=')); assertEquals(16, Base64.getUrlDecoder().decode(nonce).size)
                val input = PointsSigning.input(method, path, timestamp, nonce, body)
                val signature = PointsSigning.sign(keys.private, input)
                assertFalse(signature.contains('='))
                val bytes = Base64.getUrlDecoder().decode(signature)
                assertEquals(0x30, bytes[0].toInt()) // ASN.1 DER SEQUENCE, not raw r||s.
                assertTrue(Signature.getInstance("SHA256withECDSA").run {
                    initVerify(keys.public); update(input.toByteArray(Charsets.UTF_8)); verify(bytes)
                })
                add(buildJsonObject { put("publicKey", publicKey); put("method", method); put("path", path)
                    put("timestamp", timestamp); put("nonce", nonce); put("body", body); put("signature", signature); put("input", input) })
            }
        }
        File("build/points-vectors.json").apply { parentFile.mkdirs(); writeText(result.toString(), Charsets.UTF_8) }
    }
}
