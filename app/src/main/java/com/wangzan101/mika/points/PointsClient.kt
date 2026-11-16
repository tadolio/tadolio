// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.points

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.about.HivoConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Process-owned, private SharedPreferences queue; every mutation commits before network I/O. */
class PointsClient private constructor(context: Context) {
    private val base = HivoConfig.POINTS_URL.trim().trimEnd('/')
    // A development endpoint must never reuse another server's device id or queue.
    private val namespace = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val prefs = context.getSharedPreferences("hivo_points", Context.MODE_PRIVATE)
    private val stateKey = "queue_$namespace"
    private val deviceKey = "device_$namespace"
    private val registrationNextKey = "registration_next_$namespace"
    private val registrationAttemptsKey = "registration_attempts_$namespace"
    private val gate = Any()
    private val worker = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val key by lazy { PointsDeviceKey() }
    private val http by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build() }
    private var active: Call? = null
    private var generation = 0L
    private var started = false
    private val outbox = PointsOutbox(
        read = { prefs.getString(stateKey, "[]")!! },
        write = { check(prefs.edit().putString(stateKey, it).commit()) },
    )

    val participating: Boolean get() = prefs.getBoolean("participate", true)
    fun referralIdentity(number: String): JsonObject? {
        if (!allowed() || !prefs.contains(deviceKey)) return null
        return buildJsonObject {
            put("publicKey", key.publicKey())
            put("proof", key.sign("hivo-referral-points\n$number"))
        }
    }
    private fun allowed() = HivoConfig.pointsEnabled && participating
    fun setParticipating(value: Boolean) = synchronized(gate) {
        check(prefs.edit().putBoolean("participate", value).apply {
            if (!value) prefs.all.keys.filter { it.startsWith("queue_") }.forEach { remove(it) }
        }.commit())
        generation++
        active?.cancel()
        if (!value) outbox.clear()
    }
    fun start() = synchronized(gate) {
        if (started || !HivoConfig.pointsEnabled) return@synchronized
        started = true
        if (!participating) outbox.clear()
        // Startup and live network restoration both honor each persisted nextAt.
        val connectivity = contextService
        runCatching { connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { retry() }
        }) }
        scope.launch {
            while (isActive) {
                runCatching { flush() }
                delay(30_000)
            }
        }
    }
    private val contextService = context.getSystemService(ConnectivityManager::class.java)
    fun retry() { if (HivoConfig.pointsEnabled) scope.launch { runCatching { flush() } } }

    suspend fun enqueue(report: PointsReport) = withContext(Dispatchers.IO) {
        synchronized(gate) {
            if (!allowed()) return@withContext
            outbox.enqueue(report)
        }
        retry()
    }

    private data class Reply(val code: Int, val body: String, val retryAfterMs: Long)
    private fun request(path: String, body: String?, epoch: Long, signed: Boolean): Reply? {
        val call = synchronized(gate) {
            if (!allowed() || epoch != generation) return null
            val url = (base + path).toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            require(url.isHttps || (BuildConfig.DEBUG && url.host in setOf("127.0.0.1", "localhost")))
            val method = if (body == null) "GET" else "POST"
            val builder = Request.Builder().url(url)
            if (body != null) builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (signed) {
                val id = prefs.getString(deviceKey, null) ?: return null
                val timestamp = System.currentTimeMillis().toString()
                val nonce = PointsSigning.nonce()
                val target = url.encodedPath + (url.encodedQuery?.let { "?" + it } ?: "")
                builder.header("X-Hivo-Device", id).header("X-Hivo-Timestamp", timestamp)
                    .header("X-Hivo-Nonce", nonce)
                    .header("X-Hivo-Signature", key.sign(PointsSigning.input(method, target, timestamp, nonce, body.orEmpty())))
            }
            http.newCall(builder.build()).also { active = it }
        }
        return try {
            call.execute().use { response ->
                val retry = response.header("Retry-After")?.let { h ->
                    h.toLongOrNull()?.coerceIn(0, 86400)?.times(1000) ?: runCatching {
                        ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()
                    }.getOrDefault(0)
                } ?: 0
                Reply(response.code, response.body?.string().orEmpty(), retry).also { reply ->
                    // The server no longer knows this device (for example a reset database). Registering the same
                    // public key again returns the original id when it still exists, so this never forks identity.
                    if (signed && reply.code == 401 && reply.body.contains("invalid_auth"))
                        synchronized(gate) { if (epoch == generation) prefs.edit().remove(deviceKey).commit() }
                }
            }
        } finally { synchronized(gate) { if (active === call) active = null } }
    }
    private fun registered(epoch: Long): Boolean {
        synchronized(gate) {
            if (!allowed() || generation != epoch) return false
            if (prefs.contains(deviceKey)) return true
            if (prefs.getLong(registrationNextKey, 0) > System.currentTimeMillis()) return false
        }
        val response = runCatching {
            val body = buildJsonObject {
                put("publicKey", key.publicKey()); put("platform", "android"); put("appVersion", BuildConfig.VERSION_NAME)
            }.toString()
            request("/v1/devices", body, epoch, false)
        }.getOrNull()
        val id = response?.takeIf { it.code in 200..299 }?.let {
            runCatching { json.parseToJsonElement(it.body).jsonObject["deviceId"]?.jsonPrimitive?.content }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        return synchronized(gate) {
            if (!allowed() || generation != epoch) false
            else if (id != null) prefs.edit().putString(deviceKey, id)
                .remove(registrationNextKey).remove(registrationAttemptsKey).commit()
            else {
                val attempts = prefs.getInt(registrationAttemptsKey, 0)
                check(prefs.edit().putLong(registrationNextKey, System.currentTimeMillis() +
                    PointsRetry.delayMs(attempts, response?.retryAfterMs ?: 0))
                    .putInt(registrationAttemptsKey, (attempts + 1).coerceAtMost(100)).commit())
                false
            }
        }
    }
    private suspend fun flush() = worker.withLock {
        while (true) {
            val (item, epoch) = synchronized(gate) {
                if (!allowed()) return@withLock
                val due = outbox.entries.firstOrNull { it.nextAt <= System.currentTimeMillis() } ?: return@withLock
                due to generation
            }
            // A failed registration never discards the task. Registration is only attempted for a queued report.
            var registrationFailed = false
            val reply = runCatching {
                if (!registered(epoch)) { registrationFailed = true; null }
                else request("/v1/tasks", item.report.body(), epoch, true)
            }.getOrNull()
            synchronized(gate) {
                if (!allowed() || generation != epoch) return@withLock
                outbox.settle(item, if (registrationFailed) null else reply?.code,
                    System.currentTimeMillis(), reply?.retryAfterMs ?: 0)
            }
        }
    }
    /** Opening the page does not register a device. The first report creates its identity. */
    suspend fun balance(): PointsBalance? = withContext(Dispatchers.IO) {
        worker.withLock {
            val epoch = synchronized(gate) {
                if (!allowed() || !prefs.contains(deviceKey)) return@withContext null
                generation
            }
            val response = request("/v1/points", null, epoch, true) ?: return@withContext null
            check(response.code in 200..299)
            synchronized(gate) {
                if (!allowed() || generation != epoch) null else json.decodeFromString<PointsBalance>(response.body)
            }
        }
    }
    /** The transfer coordinator has persisted this exact request before calling export. */
    suspend fun export(body: JsonObject): JsonObject = withContext(Dispatchers.IO) { worker.withLock {
        val epoch = synchronized(gate) { generation }
        check(registered(epoch))
        val response = checkNotNull(request("/v1/points/export", body.toString(), epoch, true))
        check(response.code in 200..299) { "points_export_${response.code}" }
        json.parseToJsonElement(response.body).jsonObject
    } }
    companion object {
        @Volatile private var instance: PointsClient? = null
        fun get(context: Context): PointsClient = instance ?: synchronized(this) {
            instance ?: PointsClient(context.applicationContext).also { instance = it }
        }
    }
}
