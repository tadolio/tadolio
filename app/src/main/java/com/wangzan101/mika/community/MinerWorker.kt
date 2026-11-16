// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.core.model.ModelHttp
import android.content.Context
import com.wangzan101.mika.about.HivoConfig
import com.wangzan101.mika.family.FamilyClient
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal class MinerWorker(private val context: Context?, private val store: MinerStore, private val hubUrl:String=HivoConfig.HUB_URL, private val allowed: ()->Boolean) {
    private val http=HubTls.builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(110,TimeUnit.SECONDS).readTimeout(105,TimeUnit.SECONDS).build()
    private val sequences=mutableMapOf<String,Pair<Int,String>>()
    private fun JsonObject.str(k:String)=getValue(k).jsonPrimitive.content
    private fun call(path:String,body:JsonObject?,token:String?,quick:Boolean=false):JsonElement {
        val request=Request.Builder().url(hubUrl.trimEnd('/')+path).apply {
            if(token!=null)header("Authorization","Bearer $token")
            if(body!=null)post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        val client=if(quick)http.newBuilder().callTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).build() else http
        return client.newCall(request).execute().use {
            if (it.code == 429 && path == "/v1/node/exam/retry") {
                val result=Json.parseToJsonElement(it.body!!.string()).jsonObject
                (result["examStatus"] as? JsonObject)?.let { status -> store.examStatus=status }
                return result
            }
            check(it.isSuccessful){"miner_hub_unavailable"}
            val source=it.body!!.source();check(!source.request(2_000_001)){"miner_frame_too_large"}
            Json.parseToJsonElement(source.readUtf8())
        }
    }
    fun cancel() { http.dispatcher.cancelAll() }
    fun heartbeat() {
        val cfg=store.read();val token=cfg["token"]?.jsonPrimitive?.content?:return
        val paused=!allowed()
        val result=call("/v1/node/heartbeat",buildJsonObject {
            put("paused",paused)
            if(paused && store.phase == MinerState.KEY_BAD) put("pauseReason","key_invalid")
            else if(paused && store.pauseReason == MinerState.LIMIT) put("pauseReason","daily_limit")
        },token).jsonObject
        store.examStatus=(result["examStatus"] as? JsonObject) ?: buildJsonObject {}
        if(store.phase != MinerState.KEY_BAD) {
            store.phase=MinerState.admission(
                (result["examStatus"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull,
                result["approved"]?.jsonPrimitive?.booleanOrNull==true,
                result["examPassed"]?.jsonPrimitive?.booleanOrNull==true)
        }
    }
    fun retryExam() {
        val token=store.read().str("token")
        val result=call("/v1/node/exam/retry",buildJsonObject {},token).jsonObject
        store.examStatus=(result["examStatus"] as? JsonObject) ?: buildJsonObject {}
        if(result["ok"]?.jsonPrimitive?.booleanOrNull == true) store.phase=MinerState.WAITING_EXAM
    }
    fun pause(){
        val token=store.read()["token"]?.jsonPrimitive?.content?:return
        call("/v1/node/heartbeat",buildJsonObject{put("paused",true)},token,quick=true)
    }
    fun identity(): JsonObject {
        var cfg=store.read()
        require(MinerPolicy.validApi(cfg.str("base"),cfg.str("model"),store.keys(cfg).firstOrNull().orEmpty(),cfg["limit"]?.jsonPrimitive?.longOrNull,cfg["metered"]?.jsonPrimitive?.booleanOrNull==true))
        if(cfg["nodeId"]!=null)return cfg
        val signing=CommunityCrypto.generate();val agreement=CommunityCrypto.generate()
        fun b64(bytes:ByteArray)=Base64.getEncoder().encodeToString(bytes)
        val descriptor=buildJsonObject {
            put("version",1);put("signingKey",b64(signing.public.encoded));put("agreementKey",b64(agreement.public.encoded))
            put("kind",cfg["kind"]?.jsonPrimitive?.content ?: "compute");put("model",cfg.str("model"));put("maxConcurrent",1)
            put("platform",java.net.URI(cfg.str("base")).host);put("permission","metered_api_own_account");put("configuration",UUID.randomUUID().toString())
        }
        val signature=Signature.getInstance("SHA256withECDSA").run {
            initSign(signing.private);update(descriptor.toString().toByteArray(Charsets.UTF_8));CommunityCrypto.encode(sign())
        }
        val registered=call("/v1/node/register",buildJsonObject { put("descriptor",descriptor);put("signature",signature) },null).jsonObject
        cfg=JsonObject(cfg+mapOf("nodeId" to registered.getValue("nodeId"),"token" to registered.getValue("token"),
            "agreementPrivate" to JsonPrimitive(b64(agreement.private.encoded)),"descriptor" to descriptor))
        return store.update { current ->
            check(current["base"] == cfg["base"] && current["model"] == cfg["model"])
            JsonObject(current + cfg.filterKeys { it in setOf("nodeId", "token", "agreementPrivate", "descriptor") })
        }
    }
    fun submitCard() {
        val cfg=store.read()
        call("/v1/node/card",buildJsonObject {
            put("share",cfg["share"]?.jsonPrimitive?.intOrNull ?: 0); put("number",cfg.str("number"))
            if(cfg["kind"]?.jsonPrimitive?.content == "operator") {
                for(k in listOf("name","intro","contact","maxUsers")) cfg[k]?.let { put(k,it) }
            }
        },cfg.str("token"))
    }
    fun invite(reset:Boolean=false):String {
        val cfg=identity()
        val result=call("/v1/node/invite",buildJsonObject { if(reset)put("reset",true) },cfg.str("token")).jsonObject
        return result.str("invite").also { code -> store.update { JsonObject(it + ("invite" to JsonPrimitive(code))) } }
    }
    fun step() {
        val cfg=identity();val token=cfg.str("token")
        val family=FamilyClient.get(checkNotNull(context));family.ensureRegistered()
        submitCard()
        family.call("/v1/number/provider/confirm",buildJsonObject {put("nodeId",cfg.str("nodeId"));put("confirmed",true)})
        heartbeat()
        val stats=call("/v1/node/coins",null,token).jsonObject
        store.stats(stats["todayPending"]?.jsonPrimitive?.doubleOrNull?:0.0,stats["confirmed"]?.jsonPrimitive?.longOrNull?:0)
        // Unqualified nodes may poll examination jobs; hub enforces the admission boundary.
        if(!allowed())return
        val job=call("/v1/node/poll",null,token)
        if(job is JsonNull || !allowed())return
        // Only heartbeat examStatus identifies an entry exam. Poll payloads also carry audits.
        process(cfg,job.jsonObject)
    }
    private fun process(cfg:JsonObject,job:JsonObject) {
        val sid=job.str("sessionId");val seq=job["seq"]!!.jsonPrimitive.int;val peer=job.str("clientKey")
        val previous=sequences[sid]
        require(seq==(previous?.first?:0)+1 && (previous==null||previous.second==peer))
        val key=KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(cfg.str("agreementPrivate"))))
        val keys=CommunityCrypto.keys(key,peer,sid)
        try {
            val plain=CommunityCrypto.decrypt(keys.first,sid,"c2n",seq,job.str("ciphertext"))
            sequences[sid]=seq to peer
            if(sequences.size>4096)sequences.keys.firstOrNull{it!=sid}?.let(sequences::remove)
            val response=runCatching {
            val input=Json.parseToJsonElement(plain).jsonObject
            require(input["messages"] is JsonArray)
            val limit=store.read().getValue("limit").jsonPrimitive.long
            // Reserve BEFORE sending; timeouts/crashes retain reservation because upstream may charge.
            val inputBound=plain.toByteArray(Charsets.UTF_8).size.toLong()+1024
            val maxOutput=minOf(4096L,limit-store.used()-inputBound)
            check(allowed()){"miner_paused"}
            if(maxOutput<=0){store.reserve(limit-store.used(),limit);heartbeat();error("miner_daily_limit")}
            val reservation=inputBound+maxOutput;val day=MinerPolicy.day(System.currentTimeMillis())
            check(store.reserve(reservation,limit)){"miner_daily_limit"}
            val body=JsonObject(input.filterKeys{it !in setOf("model","stream","max_tokens","max_completion_tokens","n")}+
                mapOf("model" to cfg.getValue("model"),"stream" to JsonPrimitive(false),"max_tokens" to JsonPrimitive(maxOutput),"n" to JsonPrimitive(1)))
            val reply=store.upstream(cfg) { apiKey ->
                val request=Request.Builder().url(cfg.str("base").trimEnd('/')+"/chat/completions")
                    .header("Authorization","Bearer $apiKey")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).execute().use {
                    val source=it.body!!.source()
                    check(!source.request(1_400_001)){"miner_output_too_large"}
                    ModelHttp.Reply(it.code,it.header("Retry-After"),source.readUtf8())
                }
            }
            check(reply.ok){"miner_upstream_failed"}
            val output=Json.parseToJsonElement(reply.body).jsonObject
            val total=output["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.longOrNull
            if(total!=null)store.reconcile(reservation,total,day)
            output
            }
            val result=if(response.isSuccess)buildJsonObject{put("ok",true);put("response",response.getOrThrow())}
                else buildJsonObject{put("ok",false);put("error",if(cfg["kind"]?.jsonPrimitive?.content == "operator" && store.used() >= store.read().getValue("limit").jsonPrimitive.long) "operator_daily_limit" else "backend_failed")}
            call("/v1/node/result",buildJsonObject {
                put("sessionId",sid);put("seq",seq);put("ciphertext",CommunityCrypto.encrypt(keys.second,sid,"n2c",seq,
                    result.toString()))
            },cfg.str("token"))
        } finally {keys.first.fill(0);keys.second.fill(0)}
    }
}
