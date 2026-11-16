// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.content.Context
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.about.HivoConfig
import com.wangzan101.mika.community.CommunityCrypto
import com.wangzan101.mika.points.PointsDeviceKey
import com.wangzan101.mika.points.PointsSigning
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
internal data class FamilyPair(val data: JsonObject) {
    val id get() = data.str("id")
    val transcript get() = listOf(id, data.str("familyKey"), data.str("agreement_family"), data.str("elderKey"), data.str("agreement_elder")).joinToString("\n")
    fun elder(device: String) = data.str("elder") == device
    fun peerKey(device: String) = data.str(if (elder(device)) "familyKey" else "elderKey")
    fun peerAgreement(device: String) = data.str(if (elder(device)) "agreement_family" else "agreement_elder")
    val complete get() = data["elder"] != JsonNull && data["confirmed_family"]?.jsonPrimitive?.int == 1 && data["confirmed_elder"]?.jsonPrimitive?.int == 1
}
internal class FamilyClient private constructor(private val context: Context) : FamilyTransport {
    private val url = HivoConfig.HUB_URL.trimEnd('/')
    private val prefs = context.getSharedPreferences("hivo_family_${FamilyCrypto.hash(url)}", Context.MODE_PRIVATE)
    private val cipher = KeystoreKeyCipher("hivo-family-agreement")
    private val signing = PointsDeviceKey()
    // Idle connections are dropped after 4 s, before Node's default 5 s keep-alive closes them server-side.
    // Otherwise a request sent after a long wait (e.g. a model call) reuses a dead socket and fails with
    // "unexpected end of stream"; we cannot simply retry because these POSTs are not idempotent.
    private val http = com.wangzan101.mika.community.HubTls.builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectionPool(okhttp3.ConnectionPool(2, 4, TimeUnit.SECONDS)).callTimeout(15, TimeUnit.SECONDS).build()
    private val registered get() = prefs.getBoolean("registered", false)
    private fun identity() = FamilyCrypto.hash(signing.publicKey())
    @Synchronized internal fun privateKey(): PrivateKey {
        val encoded = prefs.getString("agreement", null)
        if (encoded != null) return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(cipher.decrypt(Base64.getDecoder().decode(encoded))))
        val pair = CommunityCrypto.generate()
        check(prefs.edit().putString("agreement", Base64.getEncoder().encodeToString(cipher.encrypt(pair.private.encoded)))
            .putString("agreementPublic", FamilyCrypto.publicKey(pair.public)).commit())
        return pair.private
    }
    internal fun moveArchive(): JsonObject = buildJsonObject {
        put("private", Base64.getEncoder().encodeToString(privateKey().encoded))
        put("public", agreement()); put("pairs",local()); put("identity",identity())
        put("removedPairs",Json.parseToJsonElement(prefs.getString("removedPairs","[]")!!))
        put("policies",buildJsonObject {
            com.wangzan101.mika.core.action.FamilyCategory.entries.forEach { category ->
                prefs.getString("policy_${category.wire}",null)?.let { put("policy_${category.wire}",it) }
            }
        })
        put("historical",historyText())
    }
    internal fun importMoveArchive(data: JsonObject) {
        val previous = Json.parseToJsonElement(data.str("historical")).jsonArray
        val history = JsonArray((previous + buildJsonObject { put("private",data.str("private"));put("public",data.str("public")) }).distinctBy { it.jsonObject.str("public") })
        val restoredPairs = JsonObject(data.getValue("pairs").jsonObject.mapValues { (_,value) ->
            val pair = value.jsonObject
            JsonObject(pair.mapValues { (field,content) ->
                if (field in listOf("family","elder") && content.jsonPrimitive.contentOrNull == data.str("identity")) JsonPrimitive(identity()) else content
            })
        })
        val edit = prefs.edit()
        (data["policies"] as? JsonObject)?.forEach { (field,value) -> edit.putString(field,value.jsonPrimitive.content) }
        check(edit.putString("removedPairs",(data["removedPairs"] ?: JsonArray(emptyList())).toString()).remove("removalProofs")
            .putString("historicalAgreements",Base64.getEncoder().encodeToString(cipher.encrypt(history.toString().toByteArray())))
            .putString("pairs",restoredPairs.toString()).commit())
    }
    private fun historyText() = prefs.getString("historicalAgreements",null)?.let {
        String(cipher.decrypt(Base64.getDecoder().decode(it)))
    } ?: "[]"
    internal fun agreementKeys(): List<PrivateKey> = listOf(privateKey()) +
        Json.parseToJsonElement(historyText()).jsonArray.map {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(it.jsonObject.str("private"))))
        }
    internal fun agreement(): String { privateKey(); return checkNotNull(prefs.getString("agreementPublic", null)) }
    internal fun call(path: String, body: JsonObject? = null, onRejected: (Int) -> Unit = {}): JsonElement {
        val parsed = url.toHttpUrl()
        require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null)
        require(parsed.isHttps || (BuildConfig.DEBUG && parsed.host in setOf("localhost", "127.0.0.1")))
        val target = (url + path).toHttpUrl()
        val method = if (body == null) "GET" else "POST"
        val bytes = body?.toString().orEmpty()
        val timestamp = System.currentTimeMillis().toString()
        val nonce = CommunityCrypto.encode(ByteArray(24).also { SecureRandom().nextBytes(it) })
        val request = Request.Builder().url(target)
            .header("x-family-key", signing.publicKey()).header("x-family-time", timestamp).header("x-family-nonce", nonce)
            .header("x-family-signature", signing.sign(PointsSigning.input(method, target.encodedPath, timestamp, nonce, bytes)))
            .method(method, body?.let { bytes.toRequestBody("application/json".toMediaType()) }).build()
        return http.newCall(request).execute().use { response ->
            val responseText = checkNotNull(response.body).string()
            if (!response.isSuccessful) {
                onRejected(response.code)
                val error = runCatching { Json.parseToJsonElement(responseText).jsonObject.str("error") }.getOrNull()
                if (error == "number_moved") com.wangzan101.mika.number.NumberClient.get(context).markMoved(runCatching { Json.parseToJsonElement(responseText).jsonObject.str("method") }.getOrNull())
                check(false) { error ?: "family_transport_failed" }
            }
            check(response.isSuccessful) { "family_transport_failed" }
            Json.parseToJsonElement(responseText)
        }
    }
    internal fun ensureRegistered() {
        if (!registered) {
            val id = call("/v1/family/register", buildJsonObject {}).jsonObject.str("deviceId")
            check(id == identity()); check(prefs.edit().putBoolean("registered", true).commit())
        }
    }
    private fun local(): JsonObject = Json.parseToJsonElement(prefs.getString("pairs", "{}")!!).jsonObject
    private val choices = FamilyPreferences(
        read = { key -> prefs.getString(key, null) },
        save = { values ->
            val edit = prefs.edit()
            values.forEach { (key, value) -> edit.putString(key, value) }
            check(edit.commit())
        },
        recipients = { pairs().filter { it.complete && it.elder(identity()) && FamilyPairProofs.valid(it) }.map { it.id } },
    )
    internal fun numberSign(value: String) = signing.sign(value)
    internal fun numberSigningKey() = signing.publicKey()
    fun policy() = choices.policy()
    fun setPolicy(category: com.wangzan101.mika.core.action.FamilyCategory, mode: com.wangzan101.mika.core.action.FamilyMode) = choices.setPolicy(category, mode)
    fun queueNotice(pair: String, category: String) = choices.queueNotice(pair, category)
    private fun save(pair: FamilyPair) {
        check(prefs.edit().putString("pairs", JsonObject(local() + (pair.id to pair.data)).toString()).commit())
    }
    fun peerName(pair: FamilyPair) = FamilyInviteEntry.name(if (pair.elder(identity())) pair.data["localName"]?.jsonPrimitive?.content.orEmpty() else "")
    fun pairs(): List<FamilyPair> = local().values.map { FamilyPair(it.jsonObject) }
    fun elderPair(): FamilyPair? = pairs().firstOrNull { it.complete && it.elder(identity()) && FamilyPairProofs.valid(it) }
    suspend fun invite(name: String = "家人"): String = withContext(Dispatchers.IO) {
        require(pairs().count { it.complete } < 5)
        ensureRegistered()
        val p = FamilyPair(call("/v1/family/invite", buildJsonObject { put("agreementKey", agreement()) }).jsonObject)
        check(p.data.str("familyKey") == signing.publicKey() && p.data.str("agreement_family") == agreement())
        val secret = CommunityCrypto.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val proof = signing.sign(FamilyInvitation.input(p.data))
        save(FamilyPair(JsonObject(p.data + mapOf(
            "inviteProof" to JsonPrimitive(proof), "localInviteSecret" to JsonPrimitive(secret),
            "localName" to JsonPrimitive(FamilyInviteEntry.name(name)),
        ))))
        val invitation = buildJsonObject {
            put("name", FamilyInviteEntry.name(name))
            put("id", p.id)
            put("familyKey", signing.publicKey())
            put("agreement", agreement())
            put("expires", p.data.getValue("expires"))
            put("proof", proof)
            put("secret", secret)
            put("fingerprint", FamilyCrypto.hash(signing.publicKey() + "\n" + agreement()))
        }
        val code = FamilyShortCode.create()
        // Persist before publishing/sharing, in the same store used by every automatic entry.
        check(contextPrefs.edit().putBoolean(FamilyShortCode.index(code), true)
            .putBoolean("own:${FamilyShortCode.index(code)}", true).commit())
        call("/v1/family/publish", buildJsonObject {
            put("id", p.id); put("proof", proof); put("index", FamilyShortCode.index(code))
            put("ciphertext", FamilyShortCode.seal(code, invitation.toString()))
        })
        runCatching { com.wangzan101.mika.number.NumberClient.get(context).moves.refreshBackup() }
        code
    }
    private val contextPrefs = context.getSharedPreferences("family_invite_seen", Context.MODE_PRIVATE)
    suspend fun resolve(code: String): JsonObject = withContext(Dispatchers.IO) {
        val normalized = FamilyShortCode.normalize(code)
        require(!contextPrefs.getBoolean("own:${FamilyShortCode.index(normalized)}", false)) { "family_own_invite" }
        ensureRegistered()
        val envelope = call("/v1/family/envelopes/${FamilyShortCode.index(normalized)}").jsonObject
        FamilyInvitation.parse(CommunityCrypto.encode(FamilyShortCode.open(normalized, envelope.str("ciphertext")).toByteArray(Charsets.UTF_8)), System.currentTimeMillis()).also {
            require(it.str("familyKey") != signing.publicKey())
        }
    }
    suspend fun join(invitation: JsonObject) = withContext(Dispatchers.IO) {
        // Recheck the signed deadline when the user actually accepts.
        FamilyInvitation.parse(CommunityCrypto.encode(invitation.toString().toByteArray(Charsets.UTF_8)), System.currentTimeMillis())
        val removed = Json.parseToJsonElement(prefs.getString("removedPairs", "[]")!!).jsonArray
        require(removed.none { it.jsonPrimitive.content == invitation.str("id") })
        require(pairs().count { it.complete } < 5)
        ensureRegistered()
        val transcript = listOf(invitation.str("id"), invitation.str("familyKey"), invitation.str("agreement"), signing.publicKey(), agreement()).joinToString("\n")
        val joined = try {
            call("/v1/family/join", buildJsonObject {
                put("id", invitation.str("id")); put("agreementKey", agreement())
                put("proof", signing.sign("family-confirm\n$transcript"))
                put("joinMac", FamilyInvitation.joinMac(invitation.str("secret"), transcript))
            }).jsonObject
        } catch (failure: Exception) {
            // A lost HTTP acknowledgement must not strand a successfully consumed invitation.
            call("/v1/family/pairs").jsonArray.map { it.jsonObject }
                .firstOrNull { it.str("id") == invitation.str("id") && it.str("elder") == identity() }
                ?: throw failure
        }
        val p = FamilyPair(JsonObject(joined + ("localInviteSecret" to invitation.getValue("secret"))))
        check(p.data.str("familyKey") == invitation.str("familyKey") && p.data.str("agreement_family") == invitation.str("agreement"))
        check(p.data.str("elderKey") == signing.publicKey() && p.data.str("agreement_elder") == agreement())
        check(p.data.str("inviteProof") == invitation.str("proof") && p.complete && FamilyPairProofs.valid(p))
        // Trust and notification outbox commit together, so restart cannot lose a new-family notice.
        choices.joined(FamilyPair(JsonObject(p.data + mapOf("localConfirmed" to JsonPrimitive(true),
            "localName" to JsonPrimitive(FamilyInviteEntry.name(invitation["name"]?.jsonPrimitive?.content.orEmpty()))))))
        runCatching { com.wangzan101.mika.number.NumberClient.get(context).moves.refreshBackup() }
    }
    fun announcement(): String? = prefs.getString("joinedAnnouncement", null)
    fun dismissAnnouncement() { check(prefs.edit().remove("joinedAnnouncement").commit()) }
    fun announcementSpoken(id: String) = prefs.getString("spokenAnnouncement", null) == id
    fun markAnnouncementSpoken(id: String) { check(prefs.edit().putString("spokenAnnouncement", id).commit()) }
    suspend fun refresh(): List<FamilyPair> = withContext(Dispatchers.IO) {
        if (!registered) return@withContext pairs()
        flushRemoved()
        val declarations = call("/v1/family/removals").jsonArray.mapNotNull { it as? JsonObject }
        val retained = FamilyRemoval.retained(pairs(), identity(), declarations)
        check(prefs.edit().putString("pairs", JsonObject(retained.associate { it.id to it.data }).toString()).commit())
        val remote = call("/v1/family/pairs").jsonArray.map { FamilyPair(it.jsonObject) }
        // Only update invitations we created or joined locally; the hub cannot introduce trusted peers.
        val next = mutableMapOf<String, JsonElement>()
        for (old in pairs()) {
            if (!old.complete && old.data.getValue("expires").jsonPrimitive.long <= System.currentTimeMillis()) continue
            val received = remote.firstOrNull { it.id == old.id }
            val p = received?.let { FamilyPair(JsonObject((it.data - "localInviteSecret") +
                old.data.filterKeys { key -> key == "localInviteSecret" || key == "localName" })) }
            if (p == null) { if (old.complete) next[old.id] = old.data; continue }
            val fields = if (old.data["elderKey"] != JsonNull) listOf("familyKey", "elderKey", "agreement_family", "agreement_elder") else listOf("familyKey", "agreement_family")
            if (!fields.all { old.data[it] == p.data[it] }) {
                val anchor = old.data["origin"] as? JsonObject ?: old.data
                val origin = p.data["origin"] as? JsonObject ?: error("family_identity_changed")
                check(listOf("id","familyKey","elderKey","agreement_family","agreement_elder","inviteProof","familyProof","elderProof").all { anchor[it] == origin[it] })
                check(FamilyPairProofs.valid(p))
            }
            if (old.complete && FamilyPairProofs.valid(old) && (!p.complete || !FamilyPairProofs.valid(p))) { next[old.id] = old.data; continue }
            // Local confirmation is authoritative even if the hub fabricates its metadata flags.
            if (old.data["localConfirmed"]?.jsonPrimitive?.boolean == true ||
                (old.data["inviteProof"] != null && old.data["inviteProof"] != JsonNull && FamilyPairProofs.valid(p))) {
                next[p.id] = JsonObject(p.data + ("localConfirmed" to JsonPrimitive(true)))
            } else next[p.id] = JsonObject((p.data - "localConfirmed") + ("confirmed_family" to JsonPrimitive(0)) + ("confirmed_elder" to JsonPrimitive(0)))
        }
        check(prefs.edit().putString("pairs", JsonObject(next).toString()).commit())
        pairs()
    }
    suspend fun remove(pair: FamilyPair) = withContext(Dispatchers.IO) {
        // Revoke locally even offline, and retry remote deletion when the page next refreshes.
        val removed = Json.parseToJsonElement(prefs.getString("removedPairs", "[]")!!).jsonArray.map { it.jsonPrimitive.content }.toSet() + pair.id
        val outbox = Json.parseToJsonElement(prefs.getString("removalProofs", "{}")!!).jsonObject
        val proof = signing.sign(FamilyRemoval.input(pair.id))
        check(prefs.edit().putString("removalProofs", JsonObject(outbox + (pair.id to JsonPrimitive(proof))).toString())
            .putString("pairs", JsonObject(local() - pair.id).toString())
            .putString("removedPairs", JsonArray(removed.map(::JsonPrimitive)).toString()).commit())
        runCatching { flushRemoved() }
        runCatching { com.wangzan101.mika.number.NumberClient.get(context).moves.refreshBackup() }
    }
    private fun flushRemoved() {
        val removed = Json.parseToJsonElement(prefs.getString("removedPairs", "[]")!!).jsonArray.map { it.jsonPrimitive.content }.toMutableSet()
        for (id in removed.toList()) {
            val outbox = Json.parseToJsonElement(prefs.getString("removalProofs", "{}")!!).jsonObject
            // Pre-upgrade pending removals can also be signed without the deleted pair.
            val proof = outbox[id]?.jsonPrimitive?.content ?: signing.sign(FamilyRemoval.input(id))
            call("/v1/family/remove", buildJsonObject { put("id", id); put("proof", proof) })
            removed.remove(id)
            check(prefs.edit().putString("removedPairs", JsonArray(removed.map(::JsonPrimitive)).toString())
                .putString("removalProofs", JsonObject(outbox - id).toString()).commit())
        }
    }
    private fun sendMessage(pair: FamilyPair, kind: String, id: String, replyTo: String, expires: Long, digest: String, category: String, approved: Boolean = false) {
        val body = buildJsonObject {
            put("id", id); put("pair", pair.id); put("kind", kind); put("replyTo", replyTo); put("expires", expires)
            put("from", identity()); put("digest", digest); put("category", category); put("approved", approved)
        }
        val sealed = FamilyCrypto.seal(privateKey(), pair.peerAgreement(identity()), id, body, signing::sign)
        call("/v1/family/send", buildJsonObject {
            put("id", id); put("pair", pair.id); put("kind", kind); put("replyTo", replyTo); put("expires", expires); put("ciphertext", sealed)
        })
    }
    suspend fun inbox(): List<JsonObject> = withContext(Dispatchers.IO) {
        if (!registered) return@withContext emptyList()
        flushRemoved()
        val pinned = pairs().filter { it.complete && FamilyPairProofs.valid(it) }
        call("/v1/family/inbox").jsonArray.mapNotNull { element -> runCatching {
            val frame = element.jsonObject
            val pair = pinned.first { it.id == frame.str("pair") }
            val body = FamilyCrypto.open(privateKey(), pair.peerAgreement(identity()), pair.peerKey(identity()), frame.str("id"), frame.str("ciphertext"))
            for (field in listOf("id", "pair", "kind", "replyTo", "expires", "from")) require(body[field] == frame[field])
            require(frame.str("to") == identity() && FamilyCrypto.hash(pair.peerKey(identity())) == body.str("from"))
            require(body.getValue("expires").jsonPrimitive.long > System.currentTimeMillis())
            body
        }.getOrNull() }
    }
    override suspend fun send(request: FamilyRequest) = withContext(Dispatchers.IO) {
        val pair = elderPair()?.takeIf { it.id == request.pair } ?: error("family_not_paired")
        sendMessage(pair, "request", request.id, "", request.expires, request.digest, request.category)
    }
    override suspend fun decisions(): List<FamilyDecision> = inbox().filter { it.str("kind") == "decision" }.map {
        FamilyDecision(it.str("replyTo"), it.str("pair"), it.str("digest"), it.getValue("approved").jsonPrimitive.boolean, it.getValue("expires").jsonPrimitive.long)
    }
    suspend fun decide(request: JsonObject, approved: Boolean) = withContext(Dispatchers.IO) {
        require(request.str("kind") == "request")
        val pair = pairs().first { it.id == request.str("pair") && !it.elder(identity()) && it.complete && FamilyPairProofs.valid(it) }
        sendMessage(pair, "decision", UUID.randomUUID().toString(), request.str("id"), request.getValue("expires").jsonPrimitive.long, request.str("digest"), request.str("category"), approved)
    }
    suspend fun flushNotices() = withContext(Dispatchers.IO) {
        val pending = choices.pending()
        for (element in pending) {
            val item = element.jsonObject
            val pair = pairs().firstOrNull { it.id == item.str("pair") && it.complete && it.elder(identity()) && FamilyPairProofs.valid(it) }
            // A retry uses a new encryption session; an acknowledgement lost in transit may produce a duplicate notice.
            if (pair != null) sendMessage(pair, "notice", UUID.randomUUID().toString(), "", System.currentTimeMillis()+600000, "", item.str("category"))
            choices.acknowledge(item.str("id"))
        }
    }
    companion object {
        @Volatile private var instance: FamilyClient? = null
        fun get(context: Context): FamilyClient {
            check(HivoConfig.communityEnabled)
            return instance ?: synchronized(this) { instance ?: FamilyClient(context.applicationContext).also { instance = it } }
        }
    }
}
