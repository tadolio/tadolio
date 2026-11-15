// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl

internal interface CommunitySession {
    val platform: String
    val model: String get() = ""
    fun complete(payload: String): String
    fun close()
}

/** Only encrypted frames leave this client; the backend API key never exists here. */
class CommunityClient(rawUrl: String, dir: File, private val cipher: KeyCipher, allowLoopback: Boolean = BuildConfig.DEBUG, private val useCommunityOnce: () -> Boolean = { false }) {
    val url = rawUrl.trimEnd('/')
    private val http = HubTls.builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(130, TimeUnit.SECONDS).readTimeout(125, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val file: File
    private var identity: JsonObject? = null
    init {
        val u = url.toHttpUrl()
        require(u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null)
        require(u.isHttps || (allowLoopback && u.host in listOf("127.0.0.1", "localhost", "::1")))
        dir.mkdirs()
        val partition = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        file = File(dir, "hub-$partition.identity")
    }
    @Synchronized private fun token(): String {
        if (identity == null && file.isFile) identity = json.parseToJsonElement(String(cipher.decrypt(file.readBytes()), Charsets.UTF_8)).jsonObject
        if (identity == null) {
            val result = request("POST", "/v1/client/register", buildJsonObject {}, null)
            val tmp = File(file.path + ".tmp"); tmp.writeBytes(cipher.encrypt(result.toString().toByteArray(Charsets.UTF_8)))
            check(tmp.renameTo(file)); identity = result
        }
        return identity!!.getValue("token").jsonPrimitive.content
    }
    /** A hub that no longer knows this client (reset or migrated) answers 401: register again once. */
    private fun authed(method: String, path: String, data: JsonObject?): JsonObject = try {
        request(method, path, data, token())
    } catch (e: IOException) {
        if (e.message != "community_hub_401") throw e
        synchronized(this) { identity = null; file.delete() }
        request(method, path, data, token())
    }
    private fun request(method: String, path: String, data: JsonObject?, bearer: String?): JsonObject {
        val req = Request.Builder().url(url + path).apply {
            if (bearer != null) header("Authorization", "Bearer $bearer")
            method(method, if (method == "GET" || method == "DELETE") null else (data ?: buildJsonObject {}).toString().toRequestBody("application/json".toMediaType()))
        }.build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code in listOf(404, 409, 503)) {
                    val code = runCatching { json.parseToJsonElement(response.peekBody(4096).string()).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    if (path in listOf("/v1/binding/preview", "/v1/binding") && code in listOf("invite_not_found", "operator_full", "operator_unavailable")) throw IOException("community_$code")
                    if(path == "/v1/session" && data?.get("kind")?.jsonPrimitive?.content == "operator" && code == "node_unavailable") throw IOException("community_operator_offline")
                    if (code == "operator_daily_limit") throw IOException("community_operator_daily_limit")
                }
                throw IOException("community_hub_${response.code}")
            }
            val source = response.body?.source() ?: throw IOException("empty_hub_response")
            if (source.request(2 * 1024 * 1024L + 1)) throw IOException("hub_response_too_large")
            return json.parseToJsonElement(source.readUtf8()).jsonObject
        }
    }
    @Synchronized fun numberCompute(): JsonObject {
        val secret = token()
        return buildJsonObject {
            put("clientId", identity!!.getValue("clientId")); put("token", secret)
        }
    }
    fun binding(): JsonObject = authed("GET", "/v1/binding", null)
    fun preview(invite: String?): JsonObject = authed("POST", "/v1/binding/preview", buildJsonObject { invite?.takeIf { it.isNotBlank() }?.let { put("invite", it) } })
    fun bind(nodeId: String, share: Int? = null): JsonObject = authed("POST", "/v1/binding", buildJsonObject { put("nodeId", nodeId); share?.let { put("share", it) } })
    fun operators(cursor: String? = null): JsonObject = request("GET", "/v1/public/operators" + (cursor?.let { "?cursor=$it" } ?: ""), null, null)
    fun previewNode(nodeId: String): JsonObject = authed("POST", "/v1/binding/preview", buildJsonObject { put("nodeId", nodeId) })
    fun report(nodeId: String, reason: String) { authed("POST", "/v1/operators/$nodeId/report", buildJsonObject { put("reason", reason) }) }
    fun unbind() { authed("DELETE", "/v1/binding", null) }
    fun open(kind: String): Session {
        val pair = CommunityCrypto.generate()
        var selectedKind=kind
        val r = try { authed("POST", "/v1/session", buildJsonObject { put("kind",kind);put("clientKey",Base64.getEncoder().encodeToString(pair.public.encoded)) }) }
        catch(e:IOException) {
            if(e.message != "community_operator_offline" || !useCommunityOnce()) throw e
            selectedKind="compute"
            authed("POST", "/v1/session",buildJsonObject {put("kind",selectedKind);put("clientKey",Base64.getEncoder().encodeToString(pair.public.encoded))})
        }
        val d = r.getValue("descriptor").jsonObject
        CommunityCrypto.verifyDescriptor(d, r.getValue("signature").jsonPrimitive.content)
        require(d.getValue("kind").jsonPrimitive.content == selectedKind)
        val id = r.getValue("sessionId").jsonPrimitive.content
        val keys = CommunityCrypto.keys(pair.private, d.getValue("agreementKey").jsonPrimitive.content, id)
        return Session(id, r.getValue("sessionToken").jsonPrimitive.content, keys, r.getValue("nodeId").jsonPrimitive.content, d.getValue("platform").jsonPrimitive.content, d.getValue("model").jsonPrimitive.content)
    }
    inner class Session(val id: String, private val bearer: String, private val keys: Pair<ByteArray, ByteArray>, val nodeId: String, override val platform: String, override val model: String = "") : CommunitySession {
        private var seq = 0
        private var failed = false
        @Synchronized override fun complete(payload: String): String {
            if (failed || seq == Int.MAX_VALUE) throw IOException("community_session_closed")
            val next = ++seq
            try {
                val r = request("POST", "/v1/relay/$id", buildJsonObject { put("seq", next); put("ciphertext", CommunityCrypto.encrypt(keys.first, id, "c2n", next, payload)) }, bearer)
                require(r.getValue("seq").jsonPrimitive.int == next)
                val clear = json.parseToJsonElement(CommunityCrypto.decrypt(keys.second, id, "n2c", next, r.getValue("ciphertext").jsonPrimitive.content)).jsonObject
                if (clear["ok"]?.jsonPrimitive?.booleanOrNull != true) throw IOException(if (clear["error"]?.jsonPrimitive?.content == "operator_daily_limit") "community_operator_daily_limit" else "community_backend_failed")
                return clear.getValue("response").toString()
            } catch (e: Exception) { failed = true; throw IOException("community_relay_failed", e) }
        }
        @Synchronized override fun close() {
            failed = true
            try { request("POST", "/v1/session/$id/close", buildJsonObject {}, bearer) } finally { keys.first.fill(0); keys.second.fill(0) }
        }
    }
}
