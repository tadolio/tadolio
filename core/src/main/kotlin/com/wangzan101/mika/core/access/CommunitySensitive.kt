// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import kotlinx.serialization.json.*

/** Community-only, deterministic text boundary. No logging of matches or original values. */
object CommunitySensitive {
    const val NOTICE = "社区模式下敏感信息已被遮挡；需要用到这些信息的事，请用自己的模型来做"
    private val otpLabel = Regex("验证码|校验码|动态码|(?<![a-z])(?:code|otp|verification)(?![a-z])", RegexOption.IGNORE_CASE)
    private val seedLabel = Regex("助记词|seed[ _-]+phrase|recovery[ _-]+phrase|mnemonic", RegexOption.IGNORE_CASE)
    private val keyLabel = Regex("私钥|private[ _-]+key", RegexOption.IGNORE_CASE)
    private val hex = Regex("(?<![a-zA-Z0-9])(?:0x)?[a-fA-F0-9]{64}(?![a-zA-Z0-9])")
    private val wif = Regex("(?<![a-zA-Z0-9])[5KL][1-9A-HJ-NP-Za-km-z]{50,51}(?![a-zA-Z0-9])")
    private val digits = Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![ -]?[0-9])")
    private val id = Regex("(?<![a-zA-Z0-9])[1-9][0-9]{16}[0-9Xx](?![a-zA-Z0-9])")
    private val otp = Regex("(?<![a-zA-Z0-9])[0-9]{4,8}(?![a-zA-Z0-9])")
    private val date = Regex("[0-9]{4}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}日?")
    private val placeholder = Regex("""\[redacted:[a-z_]+\]""")
    private val word = Regex("(?<![a-zA-Z])[a-zA-Z]+(?![a-zA-Z])")
    private val words: Set<String> by lazy {
        requireNotNull(javaClass.getResourceAsStream("/community/bip39-english.txt"))
            .bufferedReader().use { it.readLines().toSet() }.also { require(it.size == 2048) }
    }
    private data class Mask(val range: IntRange, val kind: String)

    /** Labels may be in the same node or the immediately preceding/following text node. */
    fun redact(text: String, context: String = ""): String {
        val masks = mutableListOf<Mask>()
        fun add(r: IntRange, kind: String) { masks += Mask(r, kind) }
        hex.findAll(text).forEach { add(it.range, "private_key") }
        wif.findAll(text).forEach { add(it.range, "private_key") }
        id.findAll(text).filter { validId(it.value) }.forEach { add(it.range, "id") }
        digits.findAll(text).filter { luhn(it.value) }.forEach { add(it.range, "card") }
        val lines = text.split('\n')
        var offset = 0
        lines.forEachIndexed { index, line ->
            val nearby = listOfNotNull(lines.getOrNull(index - 1), line, lines.getOrNull(index + 1)).joinToString("\n") + "\n" + context
            if (otpLabel.containsMatchIn(text) || otpLabel.containsMatchIn(nearby)) {
                val dates = date.findAll(line).map { it.range }.toList()
                otp.findAll(line).filter { m -> dates.none { m.range.first in it } }.forEach { add((offset + it.range.first)..(offset + it.range.last), "otp") }
            }
            for ((label, kind) in listOf(seedLabel to "mnemonic", keyLabel to "private_key")) {
                val own = label.find(line)
                if (own != null) {
                    val start = own.range.last + 1
                    val value = line.substring(start).trimStart(' ', ':', '：', '=', '\t')
                    if (value.isNotBlank() && !placeholder.matches(value.trim())) add((offset + line.length - value.length)..(offset + line.lastIndex), kind)
                } else if ((label.containsMatchIn(text) || label.containsMatchIn(context) || lines.getOrNull(index - 1)?.let { label.matches(it.trim().trimEnd(':', '：', '=')) } == true)
                    && line.isNotBlank() && !placeholder.matches(line.trim())) add(offset..(offset + line.lastIndex), kind)
            }
            offset += line.length + 1
        }
        // Maximal whitespace-separated runs from the built-in 2048-word English BIP39 list.
        val tokens = word.findAll(text).toList()
        var run = mutableListOf<MatchResult>()
        fun flush() {
            if (run.size in setOf(12, 15, 18, 21, 24)) add(run.first().range.first..run.last().range.last, "mnemonic")
            run = mutableListOf()
        }
        for (token in tokens) {
            if (token.value.lowercase() !in words) { flush(); continue }
            if (run.isNotEmpty() && text.substring(run.last().range.last + 1, token.range.first).any { !it.isWhitespace() }) flush()
            run += token
        }
        flush()
        if (masks.isEmpty()) return text
        // Merge overlapping detections, including a key which also passes another checksum.
        val merged = mutableListOf<Mask>()
        for (m in masks.sortedBy { it.range.first }) {
            val last = merged.lastOrNull()
            if (last != null && m.range.first <= last.range.last) merged[merged.lastIndex] = last.copy(range = last.range.first..maxOf(last.range.last, m.range.last))
            else merged += m
        }
        val out = StringBuilder(text)
        for (m in merged.asReversed()) out.replace(m.range.first, m.range.last + 1, "[redacted:${m.kind}]")
        return out.toString()
    }

    /** JSON is decoded before scanning: escaped Unicode, tree t/d/h and neighboring names retain context.
     * All string leaves are scanned; metadata is excluded only from adjacency, never from intrinsic checks.
     */
    fun payload(text: String, context: String = "", depth: Int = 0): String {
        if (depth > 8) return "[redacted:encoded]"
        val parsed = try { Json.parseToJsonElement(text) } catch (_: Exception) { return redact(text, context) }
        val leaves = mutableListOf<Pair<String, String>>()
        val textKeys = setOf("name", "t", "d", "h", "text", "description", "hint", "hintText", "value", "label", "content", "answer", "title", "message", "userNote", "placeholder")
        val metadata = setOf("ref", "snapshotId", "role", "resourceId", "package", "packageName", "type", "bounds", "centerX", "centerY", "x", "y", "width", "height", "id", "callId", "ok", "secret", "editable", "clickable", "scrollable", "focused", "enabled")
        fun isText(key: String) = key in textKeys || key !in metadata
        fun gather(e: JsonElement, key: String = "") {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> gather(v, k) }
                is JsonArray -> e.forEach { gather(it, key) }
                is JsonPrimitive -> if (e != JsonNull && isText(key)) leaves += key to e.content
            }
        }
        gather(parsed)
        // Scan together so a phrase split across nodes cannot leak, then distribute masks per leaf.
        val joined = leaves.joinToString("\n") { (key, value) -> if (keyLabel.containsMatchIn(key) || seedLabel.containsMatchIn(key) || otpLabel.containsMatchIn(key)) "$key: $value" else value }
        val safeJoined = redact(joined, context)
        val replacements = HashMap<String, String>()
        if (safeJoined != joined) {
            // Mark every participating leaf by scanning the joined stream with unique boundaries below.
            val safeValues = redactLeaves(leaves.map { it.second }, context)
            leaves.forEachIndexed { i, (_, value) -> if (safeValues[i] != value) replacements[value] = safeValues[i] }
        }
        fun rewrite(e: JsonElement, key: String = "", secret: Boolean = false): JsonElement = when (e) {
            is JsonObject -> {
                val password = secret || e["secret"] == JsonPrimitive(true) || e["password"] == JsonPrimitive(true) || e["type"] == JsonPrimitive("password")
                JsonObject(e.entries.mapIndexed { index, (k, v) ->
                    val safeKey = redact(k)
                    (if (safeKey == k) k else "$safeKey#$index") to rewrite(v, k, password)
                }.toMap())
            }
            is JsonArray -> JsonArray(e.map { rewrite(it, key, secret) })
            is JsonPrimitive -> if (e.isString) {
                val value = if (secret && isText(key)) "[redacted:password]"
                    else if (keyLabel.containsMatchIn(key) || seedLabel.containsMatchIn(key) || otpLabel.containsMatchIn(key)) redact(e.content, key)
                    else replacements[e.content] ?: if (e.content.trimStart().firstOrNull() in listOf('{', '[', '"')) payload(e.content, context, depth + 1)
                    else redact(e.content, context)
                JsonPrimitive(value)
            } else if (e != JsonNull) {
                val safe = if (secret && isText(key)) "[redacted:password]" else replacements[e.content] ?: redact(e.content, key + "\n" + context)
                if (safe != e.content) JsonPrimitive(safe) else e
            } else e
        }
        return rewrite(parsed).toString()
    }

    private fun redactLeaves(values: List<String>, context: String): List<String> {
        // A single scan with a separator preserved by span projection, rather than guessing line counts
        // after a mnemonic mask consumes several nodes.
        val safe = values.mapIndexed { i, value -> redact(value, listOfNotNull(values.getOrNull(i - 1), values.getOrNull(i + 1)).joinToString("\n") + "\n" + context) }.toMutableList()
        val joined = values.joinToString("\n")
        val tokens = word.findAll(joined).toList()
        var run = mutableListOf<MatchResult>()
        fun flush() {
            if (run.size in setOf(12, 15, 18, 21, 24)) {
                val start = run.first().range.first; val end = run.last().range.last
                var pos = 0
                for ((i, v) in values.withIndex()) {
                    if (pos <= end && pos + v.length > start) safe[i] = "[redacted:mnemonic]"
                    pos += v.length + 1
                }
            }
            run = mutableListOf()
        }
        for (t in tokens) {
            if (t.value.lowercase() !in words) { flush(); continue }
            if (run.isNotEmpty() && joined.substring(run.last().range.last + 1, t.range.first).any { !it.isWhitespace() }) flush()
            run += t
        }
        flush()
        return safe
    }

    internal fun luhn(value: String): Boolean {
        val n = value.filter(Char::isDigit)
        if (n.length !in 13..19 || n.all { it == n[0] }) return false
        return n.reversed().mapIndexed { i, c ->
            val x = (c - '0') * if (i % 2 == 1) 2 else 1
            if (x > 9) x - 9 else x
        }.sum() % 10 == 0
    }
    internal fun validId(value: String): Boolean {
        if (!Regex("[1-9][0-9]{16}[0-9Xx]").matches(value)) return false
        if (value.take(2).toInt() !in setOf(11,12,13,14,15,21,22,23,31,32,33,34,35,36,37,41,42,43,44,45,46,50,51,52,53,54,61,62,63,64,65,71,81,82)) return false
        try { java.time.LocalDate.of(value.substring(6,10).toInt(),value.substring(10,12).toInt(),value.substring(12,14).toInt()) } catch (_: Exception) { return false }
        val weights = listOf(7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2)
        return "10X98765432"[value.take(17).mapIndexed { i,c -> (c-'0')*weights[i] }.sum()%11] == value.last().uppercaseChar()
    }
}
