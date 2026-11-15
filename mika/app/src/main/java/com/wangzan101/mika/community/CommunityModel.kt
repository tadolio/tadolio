// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.core.model.*
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer

/** Reuse the existing OpenAI serializer/parser, but intercept before any network operation. */
class CommunityModel internal constructor(private val openSession: () -> CommunitySession, private val onPlatform: (String) -> Unit = {}) : ChatModel {
    constructor(client: CommunityClient, kind: String, onPlatform: (String) -> Unit = {}) : this({ client.open(kind) }, onPlatform)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var assignedModel = ""
    override val name get() = assignedModel
    override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32000, reasoning = false)
    private var session: CommunitySession? = null
    private var closed = false
    private val transport = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
        synchronized(this) { try {
            if (closed) throw IOException("community_model_closed")
            val current = session ?: openSession().also { session = it; assignedModel = it.model; onPlatform(it.platform) }
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            val answer = current.complete(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer.toResponseBody("application/json".toMediaType())).build()
        } catch (error: Throwable) {
            // No unchecked throwable may escape onto OkHttp's Dispatcher thread.
            val discarded = session.also { session = null }
            discarded?.let { old -> cleanup.launch { runCatching { old.close() } } }
            throw IOException("community_request_failed", error)
        } }
    }.build()
    private val delegate = OpenAiCompatModel("https://community.invalid/v1", null, "community", capabilities, transport)
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        // Only explicitly attached user photos may cross this boundary.
        if (messages.any { m -> m.parts.any { it is Part.Image && (!it.userAttachment || m.author != Author.User) } }) throw IOException("community_images_forbidden")
        return delegate.complete(system, messages, tools)
    }
    suspend fun close() = withContext(NonCancellable) {
        // Return to a non-cancelled context after IO, so the owner's remaining finally also runs.
        withContext(Dispatchers.IO) {
            synchronized(this@CommunityModel) { closed = true; runCatching { session?.close() }; session = null }
        }
    }
}
