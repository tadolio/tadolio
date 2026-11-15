// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import android.content.Context
import com.wangzan101.mika.family.FamilyClient
import com.wangzan101.mika.family.FamilyCrypto
import com.wangzan101.mika.family.str
import com.wangzan101.mika.community.CommunityCrypto
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

internal class NumberMoveClient(private val context: Context, private val client: NumberClient) {
    private val recoveryLock = Mutex()
    private val handoffLock = Mutex()
    private val family = FamilyClient.get(context)
    private val prefs = context.getSharedPreferences("hivo_move_${FamilyCrypto.hash(com.wangzan101.mika.about.HivoConfig.HUB_URL)}", Context.MODE_PRIVATE)
    private val cipher = KeystoreKeyCipher("hivo-number-recovery")
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(s: String) = Base64.getDecoder().decode(s)
    private fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
    private fun save(key: String, value: String) { check(prefs.edit().putString(key, encode(cipher.encrypt(value.toByteArray()))).commit()) }
    private fun read(key: String) = prefs.getString(key, null)?.let { String(cipher.decrypt(decode(it))) }
    private fun normalize(value: String) = NumberMoveCodes.normalize(value)
    private fun derived(code: String, salt: String) = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(normalize(code).toCharArray(),salt.toByteArray(),210000,256)).encoded
    private fun encrypt(code: String, plain: String): String {
        val salt = random(16).joinToString("") { "%02x".format(it) }
        val iv = random(12)
        val value = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE,SecretKeySpec(derived(code,salt),"AES"),GCMParameterSpec(128,iv))
            updateAAD("hivo-number-backup-v1".toByteArray())
            doFinal(plain.toByteArray())
        }
        return buildJsonObject { put("salt",salt)
        put("iv",encode(iv))
        put("ciphertext",encode(value)) }.toString()
    }
    private fun decrypt(code: String, encrypted: String): JsonObject {
        val data = Json.parseToJsonElement(encrypted).jsonObject
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,SecretKeySpec(derived(code,data.str("salt")),"AES"),GCMParameterSpec(128,decode(data.str("iv"))))
            updateAAD("hivo-number-backup-v1".toByteArray())
            doFinal(decode(data.str("ciphertext")))
        }
        return Json.parseToJsonElement(String(plain)).jsonObject
    }
    private fun archive(recoveryPrivate: String? = read("recoveryPrivate")) = buildJsonObject {
        put("number",client.mine())
        put("settings",client.movePreferences())
        put("family",family.moveArchive())
        put("messages",JsonArray(client.store.messages()))
        put("recoveryPublic",read("recoveryPublic") ?: "")
        put("recoveryPrivate",recoveryPrivate ?: "")
        put("code",read("code") ?: "")
    }
    private fun importArchive(data: JsonObject, number: String) {
        require(data.str("number") == number)
        family.importMoveArchive(data.getValue("family").jsonObject)
        client.importMovePreferences(data.getValue("settings").jsonObject)
        data.getValue("messages").jsonArray.forEach { val m = it.jsonObject
        client.store.add(m,m["outgoing"]?.jsonPrimitive?.booleanOrNull == true) }
        check(prefs.edit().putString("number",number).commit())
        if (data.str("code").isNotBlank()) save("code",data.str("code"))
        if (data.str("recoveryPrivate").isNotBlank()) save("recoveryPrivate",data.str("recoveryPrivate"))
        client.acceptMovedNumber(number)
    }
    suspend fun ensureRecovery(replace: Boolean = false) = withContext(Dispatchers.IO) { recoveryLock.withLock {
        family.ensureRegistered()
        if (client.mine().isEmpty()) return@withLock
        val status = family.call("/v1/number/recovery").jsonObject
        val configured = status["configured"]?.jsonPrimitive?.boolean == true
        if (!replace && configured && !prefs.getBoolean("recoveryPending",false) && read("code") != null && prefs.getString("number",null) == client.mine()) return@withLock
        // Persist before sending so a lost HTTP response cannot discard the only displayable copy.
        if (replace || read("code") == null || prefs.getString("number",null) != client.mine()) {
            val code = NumberMoveCodes.newRecovery()
            val recovery = CommunityCrypto.generate()
            val edit = prefs.edit()
            for ((field,value) in mapOf("code" to code,"recoveryPrivate" to encode(recovery.private.encoded),"recoveryPublic" to FamilyCrypto.publicKey(recovery.public))) {
                edit.putString(field,encode(cipher.encrypt(value.toByteArray())))
            }
            check(edit.putString("number",client.mine()).putBoolean("recoveryPending",true)
                .putBoolean("recoverySaved",false).commit())
        }
        configure(replace || configured)
        check(prefs.edit().remove("recoveryPending").commit())
    } }
    private fun configure(replace: Boolean) {
        val code = checkNotNull(read("code"))
        val signing = checkNotNull(read("recoveryPublic"))
        val salt = random(16).joinToString("") { "%02x".format(it) }
        family.call("/v1/number/recovery/configure",buildJsonObject {
            put("salt",salt)
            put("verifier",derived(code,salt).joinToString("") { "%02x".format(it) })
            put("backup",encrypt(code,archive().toString()))
            put("publicKey",signing)
            put("delegation",family.numberSign("hivo-recovery\n${client.mine()}\n$signing"))
            put("replace",replace)
        })
        check(prefs.edit().putString("savedNumber",client.mine()).putBoolean("recoverySaved",false).commit())
    }
    suspend fun refreshBackup() = withContext(Dispatchers.IO) { recoveryLock.withLock {
        val code = read("code") ?: return@withLock
        if (prefs.getString("number",null) != client.mine() || prefs.getBoolean("recoveryPending",false)) return@withLock
        family.call("/v1/number/recovery/backup",buildJsonObject { put("backup",encrypt(code,archive().toString())) })
    } }
    fun locallySaved() = prefs.getString("savedNumber",null) == client.mine() && prefs.getBoolean("recoverySaved",false)
    suspend fun status() = withContext(Dispatchers.IO) { ensureRecovery()
        family.call("/v1/number/recovery").jsonObject.also {
            check(prefs.edit().putString("savedNumber",client.mine())
                .putBoolean("recoverySaved",it["saved"]?.jsonPrimitive?.boolean == true).commit())
        }
    }
    fun viewed() = read("code")?.let { prefs.getString("viewed",null) == FamilyCrypto.hash(it) } == true
    fun code(): String {
        val code = checkNotNull(read("code"))
        check(prefs.edit().putString("viewed",FamilyCrypto.hash(code)).commit())
        return code.chunked(4).joinToString("-")
    }
    suspend fun saved() = withContext(Dispatchers.IO) { refreshBackup()
        family.call("/v1/number/recovery/saved",buildJsonObject {})
        check(prefs.edit().putString("savedNumber",client.mine()).putBoolean("recoverySaved",true).commit())
    }
    fun transferActive() = prefs.getLong("transferUntil",0) > System.currentTimeMillis()
    suspend fun create() = withContext(Dispatchers.IO) { handoffLock.withLock {
        // Reopening the page must retain a code that a new phone may already be submitting.
        val existing = read("outgoingTransfer")?.let { Json.parseToJsonElement(it).jsonObject }
        if (existing != null && existing.str("number") == client.mine() &&
            (existing["expires"]?.jsonPrimitive?.longOrNull ?: 0) > System.currentTimeMillis()) return@withLock existing
        ensureRecovery()
        family.call("/v1/number/transfer/create",buildJsonObject {}).jsonObject.also {
            save("outgoingTransfer", it.toString())
            check(prefs.edit().putLong("transferUntil",it.getValue("expires").jsonPrimitive.long).commit())
            NumberPolling.schedule(context)
        }
    } }
    suspend fun pending() = withContext(Dispatchers.IO) {
        val rows = family.call("/v1/number/transfer/pending").jsonArray
        rows.firstOrNull()?.jsonObject?.str("id")?.let { id ->
            if (prefs.getString("notifiedTransfer",null) != id) {
                NumberPolling.notifyTransfer(context)
                check(prefs.edit().putString("notifiedTransfer",id).commit())
            }
        }
        rows
    }
    private fun proposal(number: String) = buildJsonObject {
        put("agreementKey",family.agreement())
        put("proof",family.numberSign(NumberProtocol.binding(number,family.agreement())))
        com.wangzan101.mika.community.CommunityClients.get(context).numberCompute().forEach { (k,v) -> put(k,v) }
    }
    private fun requestRemote(code: String): JsonObject {
        val number = family.call("/v1/number/transfer/resolve",buildJsonObject { put("code",normalize(code)) }).jsonObject.str("number")
        return family.call("/v1/number/transfer/request",JsonObject(proposal(number) + buildJsonObject {
            put("code",normalize(code))
            put("model",android.os.Build.MODEL.take(80))
        })).jsonObject.also { check(prefs.edit().putString("pendingTransfer",it.toString()).commit()) }
    }
    fun receiving() = prefs.contains("pendingCode") || prefs.contains("pendingRecovery") || pendingTransfer() != null
    fun recovering() = prefs.contains("pendingRecovery")
    suspend fun resumeRecovery() {
        val pending = read("pendingRecovery")?.let { Json.parseToJsonElement(it).jsonObject } ?: return
        restore(pending.str("number"),pending.str("code"))
    }
    suspend fun request(code: String) = withContext(Dispatchers.IO) { handoffLock.withLock {
        require(NumberMoveCodes.transferValid(code))
        family.ensureRegistered()
        require(!recovering())
        require(read("pendingCode")?.let { it == normalize(code) } != false)
        save("pendingCode",normalize(code))
        requestRemote(code)
    } }
    fun pendingTransfer() = prefs.getString("pendingTransfer",null)?.let { Json.parseToJsonElement(it).jsonObject }
    suspend fun decide(row: JsonObject, accept: Boolean) = withContext(Dispatchers.IO) {
        val p = row.getValue("proposal").jsonObject
        if (accept) client.flushForMove()
        val backup = if (accept) FamilyCrypto.seal(family.privateKey(),p.str("agreementKey"),"number-transfer:${row.str("id")}",archive(),family::numberSign) else ""
        family.call("/v1/number/transfer/decide",buildJsonObject {
            put("id",row.str("id"))
            put("accept",accept)
            if (accept) {
                put("signature",family.numberSign(NumberMovements.input(row.str("number"),family.numberSigningKey(),p.str("signingKey"),p.str("agreementKey"))))
                // Outer envelope carries the pinned old keys needed to authenticate and decrypt the handoff.
                put("backup",buildJsonObject { put("agreement",family.agreement())
                put("signing",family.numberSigningKey())
                put("ciphertext",backup) }.toString())
            }
        })
        check(prefs.edit().remove("outgoingTransfer").remove("transferUntil").commit())
    }
    suspend fun finishTransfer(): String? = withContext(Dispatchers.IO) { handoffLock.withLock {
        val pending = pendingTransfer() ?: read("pendingCode")?.let { code ->
            try { requestRemote(code) }
            catch (e: Exception) {
                if (e.message == "transfer_unavailable" || e.message == "number_move_locked") {
                    check(prefs.edit().remove("pendingCode").commit())
                    return@withLock "expired"
                }
                throw e
            }
        } ?: return@withLock null
        val result = family.call("/v1/number/transfer/status",buildJsonObject { put("id",pending.str("id")) }).jsonObject
        when (result.str("state")) {
            "completed" -> {
                val envelope = Json.parseToJsonElement(result.str("backup")).jsonObject
                val data = FamilyCrypto.open(family.privateKey(),envelope.str("agreement"),envelope.str("signing"),"number-transfer:${pending.str("id")}",envelope.str("ciphertext"))
                importArchive(data,result.str("number"))
                family.refresh()
                // Re-attest the existing recovery delegation with the new device key.
                // Public key is included in the authenticated source archive.
                data["recoveryPublic"]?.jsonPrimitive?.contentOrNull?.let { save("recoveryPublic",it) }
                if (read("recoveryPublic") != null) recoveryLock.withLock { configure(true) } else ensureRecovery(true)
                check(prefs.edit().remove("pendingTransfer").remove("pendingCode").commit())
                "completed"
            }
            "rejected", "expired", "cancelled" -> { prefs.edit().remove("pendingTransfer").remove("pendingCode").apply()
            result.str("state") }
            else -> result.str("state")
        }
    } }
    suspend fun restore(number: String, value: String) = withContext(Dispatchers.IO) { handoffLock.withLock {
        family.ensureRegistered()
        val code = normalize(value)
        require(NumberMoveCodes.recoveryValid(code))
        val previous = read("pendingRecovery")?.let { Json.parseToJsonElement(it).jsonObject }
        require(previous == null || (previous.str("number") == number && previous.str("code") == code))
        require(pendingTransfer() == null && read("pendingCode") == null)
        save("pendingRecovery",buildJsonObject { put("number",number)
        put("code",code) }.toString())
        val result = read("restoreResult")?.let { Json.parseToJsonElement(it).jsonObject } ?: try {
            val prepare = family.call("/v1/number/recovery/prepare",buildJsonObject { put("number",number)
            put("code",code) }).jsonObject
            val data = decrypt(code,prepare.str("backup"))
            val recovery = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decode(data.str("recoveryPrivate"))))
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(recovery)
                update(NumberMovements.input(number,prepare.str("signingKey"),family.numberSigningKey(),family.agreement()).toByteArray())
                CommunityCrypto.encode(sign())
            }
            family.call("/v1/number/recovery/restore",JsonObject(proposal(number)+buildJsonObject { put("number",number)
            put("code",code)
            put("signature",signature) })).jsonObject

        } catch (e: Exception) {
            if (e.message in setOf("recovery_unavailable","number_move_locked","family_request_rejected"))
                check(prefs.edit().remove("pendingRecovery").commit())
            throw e
        }
        save("restoreResult",result.toString())
        importArchive(decrypt(code,result.str("backup")),number)
        family.refresh()
        ensureRecovery(true)
        check(prefs.edit().remove("pendingRecovery").remove("restoreResult").commit())
    } }
}
