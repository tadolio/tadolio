// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** User-facing copy only. Never use this for model prompts, tool results or detection tokens.
 * The Android host installs its resource lookup. Standalone callers keep legacy recording text.
 */
object UserText {
    @Volatile var source: ((String, Array<out Any?>) -> String)? = null
    private val legacy by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/i18n/user-zh.json")).bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }
    fun get(key: String, vararg args: Any?): String = source?.invoke(key, args)
        ?: legacy.getValue(key).let { if (args.isEmpty()) it else String.format(Locale.ROOT, it, *args) }
}
