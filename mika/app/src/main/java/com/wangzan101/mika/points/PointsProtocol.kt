// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.points

import com.wangzan101.mika.core.agent.*
import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64

/** Only this DTO is ever serialized as a task request. */
@Serializable
data class PointsReport(
    val taskId: String, val startedAt: Long, val endedAt: Long,
    val outcome: String, val judge: String, val steps: Int,
    val computeSource: String, val appVersion: String,
) {
    fun body(): String = Json.encodeToString(this)
}

object PointsMapping {
    fun outcome(end: TaskState): String = when (end) {
        is TaskState.Succeeded -> "done"
        is TaskState.Failed -> if (end.kind == FailKind.GaveUp) "abandoned" else "failed"
        TaskState.Cancelled -> "cancelled"
        else -> error("A points report requires a terminal state")
    }
    fun judge(outcome: RunOutcome?): String = when (outcome) {
        RunOutcome.Done -> "success"
        RunOutcome.Partial -> "partial"
        RunOutcome.NotPossible -> "fail"
        RunOutcome.Unknown, null -> "none"
    }
    fun source(kind: ProviderConfig.Kind): String = when (kind) {
        ProviderConfig.Kind.Community -> "community"
        ProviderConfig.Kind.ChatGpt -> "chatgpt"
        ProviderConfig.Kind.Xai -> "xai"
        else -> "byok"
    }
}

object PointsSigning {
    fun input(method: String, path: String, timestamp: String, nonce: String, body: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return listOf(method, path, timestamp, nonce, hash).joinToString("\n")
    }
    fun nonce(): String = url64(ByteArray(16).also { SecureRandom().nextBytes(it) })
    fun sign(key: PrivateKey, input: String): String = url64(Signature.getInstance("SHA256withECDSA").run {
        initSign(key); update(input.toByteArray(Charsets.UTF_8)); sign()
    })
    private fun url64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

@Serializable
data class PointsPending(val report: PointsReport, val attempts: Int = 0, val nextAt: Long = 0)

/** Pure queue transitions shared by the persisted queue and JVM tests. */
object PointsRetry {
    fun after(item: PointsPending, code: Int?, now: Long, retryAfterMs: Long = 0): PointsPending? {
        if (code != null && (code in 200..299 || (code in 400..499 && code !in setOf(401, 408, 425, 429)))) return null
        return item.copy(attempts = (item.attempts + 1).coerceAtMost(100),
            nextAt = now + delayMs(item.attempts, retryAfterMs))
    }
    fun delayMs(attempts: Int, retryAfterMs: Long = 0): Long = maxOf(
        (30_000L * (1L shl attempts.coerceIn(0, 7))).coerceAtMost(3_600_000L),
        retryAfterMs.coerceIn(0, 86_400_000))
}

@Serializable
data class PointsBalance(val confirmed: Int, val pending: Int, val todayCounted: Int, val dailyCap: Int, val streakDays: Int)
