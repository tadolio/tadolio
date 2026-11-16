// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.PhoneOcr
import com.wangzan101.mika.community.*
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Accept only a deliberately shared single item. Original image bytes never leave the device. */
class ScamShareActivity : ComponentActivity() {
    private var shared by mutableStateOf<String?>(null)
    private var invalid by mutableStateOf(false)
    private var state by mutableStateOf<ScamState>(ScamState.Ready)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val code = FamilyInviteEntry.extract(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
            if (code != null) {
                startActivity(Intent(this, com.wangzan101.mika.ui.MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW).setData(Uri.parse(FamilyInviteEntry.link(code))))
                finish()
                return
            }
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            shared = try { withContext(Dispatchers.IO) { readShared(intent) } } catch (_: Exception) { invalid = true; null }
        }
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.scam_title), fontSize = 30.sp)
                    Text(stringResource(R.string.scam_privacy), fontSize = 22.sp)
                    if (invalid) Text(stringResource(R.string.scam_unreadable), fontSize = 24.sp)
                    shared?.let { Text(com.wangzan101.mika.ui.DisplayRedactions.forUi(it), fontSize = 22.sp) }
                    Button({ check() }, enabled = shared != null && state !is ScamState.Checking) {
                        Text(stringResource(R.string.scam_check), fontSize = 24.sp)
                    }
                    when (val s = state) {
                        ScamState.Ready -> Unit
                        ScamState.Checking -> Text(stringResource(R.string.scam_checking), fontSize = 26.sp)
                        ScamState.Failed -> Text(stringResource(R.string.scam_failed), fontSize = 26.sp)
                        is ScamState.Result -> {
                            Text(stringResource(when (s.result.verdict) {
                                ScamVerdict.Suspicious -> R.string.scam_suspicious
                                ScamVerdict.Uncertain -> R.string.scam_uncertain
                                ScamVerdict.Normal -> R.string.scam_normal
                            }), fontSize = 32.sp)
                            Text(com.wangzan101.mika.ui.DisplayRedactions.forUi(s.result.reason), fontSize = 24.sp)
                            Text(com.wangzan101.mika.ui.DisplayRedactions.forUi(s.result.advice), fontSize = 24.sp)
                            Text(stringResource(R.string.scam_limit), fontSize = 22.sp)
                        }
                    }
                    TextButton({ finish() }) { Text(stringResource(R.string.ok_got_it), fontSize = 22.sp) }
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    private suspend fun readShared(input: Intent): String {
        require(ScamCheck.accepts(input.action, input.type))
        val text = when {
            input.type == "text/plain" -> input.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            input.type?.startsWith("image/") == true -> {
                val uri = input.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: error("missing_image")
                require(uri.scheme == "content")
                val bytes = contentResolver.openInputStream(uri)!!.use { inputStream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= 10 * 1024 * 1024)
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                require(bytes.size <= 10 * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40000000)
                val options = BitmapFactory.Options().apply { inSampleSize = maxOf(bounds.outWidth, bounds.outHeight).let { if (it > 2400) (it + 2399) / 2400 else 1 } }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("decode_failed")
                PhoneOcr.read(bitmap).joinToString("\n") { it.text }
            }
            else -> error("unsupported_share")
        }
        return ScamCheck.sanitize(text)
    }
    private fun check() {
        val text = shared ?: return
        if (state is ScamState.Checking) return
        state = ScamState.Checking
        lifecycleScope.launch {
            var community: CommunityModel? = null
            try {
                val store = ProviderStore.get(this@ScamShareActivity)
                val cfg = store.configFor(ModelRole.Main)
                val model = withContext(Dispatchers.IO) {
                    if (cfg?.kind == ProviderConfig.Kind.Community) CommunityModel(CommunityClients.get(this@ScamShareActivity), cfg.model).also { community = it }
                    else store.modelFor(ModelRole.Main)
                } ?: error("no_model")
                state = ScamCheck.state(model, text, getString(R.string.scam_prompt))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { state = ScamState.Failed }
            finally { community?.close() }
        }
    }
}
