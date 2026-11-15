// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ResFileTexts
import java.io.IOException
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MinerSetupTest {
    private fun validator(status: Int = 200, body: String = """{"data":[{"id":"custom-model"}]}""",
                          inspect: (okhttp3.Request) -> Unit = {}) = MinerKeyValidator(
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())

    @Test fun platformSelectionProvidesAddressModelAndKeyLinkWithoutPrices() {
        assertEquals(setOf("openrouter", "siliconflow", "deepseek", "openai", "groq"), MinerPlatforms.all.map { it.id }.toSet())
        for (platform in MinerPlatforms.all) {
            assertEquals(platform, MinerPlatforms.find(platform.base + "/"))
            assertTrue(platform.keyUrl.startsWith("https://"))
            assertTrue(MinerPolicy.canEnable(platform.base, platform.model, "test-only", 2_000_000, 20, "12345678"))
            assertTrue(ResFileTexts.english.string(platform.description, emptyArray()).isNotBlank())
        }
        assertEquals("https://api.groq.com/openai/v1", MinerPlatforms.all.last().base)
        assertEquals("deepseek/deepseek-v4-flash", MinerPlatforms.all.first().model)
        assertNull(MinerPlatforms.price(MinerPlatforms.all.first().base, MinerPlatforms.all.first().model))
    }

    @Test fun defaultBudgetIsActualValueAndSavedBudgetIsRetained() {
        assertEquals("2000000", MinerPolicy.initialLimit(""))
        assertEquals("750000", MinerPolicy.initialLimit("750000"))
        assertFalse(MinerPolicy.canEnable("https://api.example.com/v1", "m", "test-only", 0, 20, "12345678"))
    }

    @Test fun advancedCustomEndpointWorksWithoutListedPlatformOrPrice() {
        val base = "https://custom.example.com/compatible/v1"
        assertNull(MinerPlatforms.find(base))
        assertNull(MinerPlatforms.price(base, "custom-model"))
        assertTrue(MinerPolicy.canEnable(base, "custom-model", "test-only", 2_000_000, 20, "12345678"))
        val check = validator { request ->
            assertEquals("$base/models", request.url.toString())
            assertEquals("Bearer test-only", request.header("Authorization"))
            assertEquals("GET", request.method)
        }
        assertEquals(MinerKeyValidator.Result.Valid, check.validate(base, "custom-model", "test-only"))
    }

    @Test fun failedKeyOrBalanceShowsFriendlyLocalizedMessage() {
        for (status in listOf(401, 402, 403, 429, 500)) {
            val result = validator(status).validate("https://api.example.com/v1", "custom-model", "test-only")
            assertEquals(MinerKeyValidator.Result.Failed, result)
            assertEquals(R.string.miner_key_failed, result.message)
            assertTrue(ResFileTexts.chinese.string(result.message!!, emptyArray()).contains("Key 不对或余额不足"))
        }
        assertEquals(MinerKeyValidator.Result.Failed, validator(body = "{}").validate("https://api.example.com/v1", "custom-model", "test-only"))
        assertEquals(MinerKeyValidator.Result.Failed, validator(body = "not json").validate("https://api.example.com/v1", "custom-model", "test-only"))
        val offline = MinerKeyValidator(OkHttpClient.Builder().addInterceptor { throw IOException("test") }.build())
        assertEquals(MinerKeyValidator.Result.Failed, offline.validate("https://api.example.com/v1", "custom-model", "test-only"))
    }

    @Test fun openRouterUsesAuthenticatedKeyEndpointAndRejectsExhaustedLimit() {
        val base = MinerPlatforms.all.first().base
        val check = validator(body = """{"data":{"limit_remaining":1}}""") {
            assertEquals("$base/key", it.url.toString())
            assertEquals("Bearer test-only", it.header("Authorization"))
        }
        assertEquals(MinerKeyValidator.Result.Valid, check.validate(base, "model", "test-only"))
        assertEquals(MinerKeyValidator.Result.Failed,
            validator(body = """{"data":{"limit_remaining":0}}""").validate(base, "model", "test-only"))
        assertEquals(MinerKeyValidator.Result.Failed,
            validator(body = """{"data":[]}""").validate(base, "model", "test-only"))
    }

    @Test fun savedKeyDoesNotFollowPlatformChanges() {
        assertEquals("", MinerPlatforms.effectiveKey("", "https://new.example.com/v1", "https://old.example.com/v1", "test-only"))
        assertEquals("test-only", MinerPlatforms.effectiveKey("", "https://old.example.com/v1/", "https://old.example.com/v1", "test-only"))
        assertEquals("new-test", MinerPlatforms.effectiveKey(" new-test ", "https://new.example.com/v1", "https://old.example.com/v1", "test-only"))
    }

    @Test fun subscriptionPlatformsAreAbsentAndCannotBeEnabledAsCustomApis() {
        for (domain in listOf("chatgpt.com", "chat.openai.com", "grok.com")) {
            assertFalse(MinerPlatforms.all.any { it.base.contains(domain) })
            assertFalse(MinerPolicy.canEnable("https://$domain", "model", "test-only", 2_000_000, 20, "12345678"))
        }
    }

    @Test fun pricesAreOnlyUsedForExactPresetModelAndEndpoint() {
        for (platform in MinerPlatforms.all.drop(1)) {
            val price = MinerPlatforms.price(platform.base, platform.model)!!
            assertNotNull(MinerPricing.estimate(2_000_000, price.input, price.output))
            assertNull(MinerPlatforms.price(platform.base, "different-model"))
            assertNull(MinerPlatforms.price("https://custom.example.com", platform.model))
        }
    }

    @Test fun simplifiedCopyAndOtherLocalesUseEnglishFallback() {
        assertEquals("别人让 Tadolio 办事时，由你的手机用你的 API Key 调用模型。花的是你 API 账户里的钱，你会收到金币。Key 只存在这台手机上，平台不保管。", ResFileTexts.chinese.strings.getValue("miner_intro"))
        for (folder in listOf("values-ja", "values-ko", "values-ar", "values-fa", "values-es", "values-pt", "values-ru", "values-in")) {
            val texts = ResFileTexts(folder, Locale.ENGLISH)
            for (name in ResFileTexts.english.strings.keys.filter { it.startsWith("miner_") }) {
                val id = R.string::class.java.getField(name).getInt(null)
                assertEquals(name, ResFileTexts.english.strings.getValue(name), texts.string(id, emptyArray()))
            }
        }
    }
}
