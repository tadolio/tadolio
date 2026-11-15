// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.R

/** Platform configuration and public text-token prices per million, updated 2026-11-15.
 * Estimates exclude cache discounts and extra tools/images. DeepSeek uses peak cache-miss rates.
 * OpenRouter prices always come from its live public catalogue, never this table.
 * Sources: https://www.siliconflow.cn/pricing (Qwen3.6-35B-A3B)
 * https://api-docs.deepseek.com/quick_start/pricing/ (deepseek-flash, peak)
 * https://developers.openai.com/api/docs/models/gpt-4o-mini
 * https://console.groq.com/docs/models (GPT OSS 120B)
 * Endpoints: respective API docs; Groq https://console.groq.com/docs/openai
 */
internal object MinerPlatforms {
    const val UPDATED = "2026-11-15"
    data class Platform(val id: String, val name: String, val description: Int, val base: String,
                        val model: String, val keyUrl: String, val price: Price? = null)
    data class Price(val input: Double, val output: Double, val currency: String)
    val all = listOf(
        Platform("openrouter", "OpenRouter", R.string.miner_platform_openrouter,
            "https://openrouter.ai/api/v1", "deepseek/deepseek-v4-flash", "https://openrouter.ai/settings/keys"),
        Platform("siliconflow", "SiliconFlow", R.string.miner_platform_siliconflow,
            "https://api.siliconflow.cn/v1", "Qwen/Qwen3.6-35B-A3B", "https://cloud.siliconflow.cn/account/ak",
            Price(1.8, 10.8, "CNY")),
        Platform("deepseek", "DeepSeek", R.string.miner_platform_deepseek,
            "https://api.deepseek.com", "deepseek-flash", "https://platform.deepseek.com/api_keys",
            Price(0.3, 1.2, "USD")),
        Platform("openai", "OpenAI", R.string.miner_platform_openai,
            "https://api.openai.com/v1", "gpt-4o-mini", "https://platform.openai.com/api-keys",
            Price(0.15, 0.6, "USD")),
        Platform("groq", "Groq", R.string.miner_platform_groq,
            "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "https://console.groq.com/keys",
            Price(0.15, 0.6, "USD"))
    )
    fun find(base: String) = all.firstOrNull { it.base == base.trimEnd('/') }
    fun price(base: String, model: String) = find(base)?.takeIf { it.model == model }?.price

    // A saved credential must never follow a platform/address change to a different server.
    fun effectiveKey(typed: String, base: String, savedBase: String, savedKey: String) =
        typed.trim().ifBlank { if (base.trimEnd('/') == savedBase.trimEnd('/')) savedKey else "" }
}
