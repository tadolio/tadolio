package com.wangzan101.mika.core.agent

/**
 * 回复正文里漏出来的工具调用:有的推理服务偶尔没把模型的工具调用解析出来,调用原样当文字回来了。
 * 这样的回复不是回答,不能交给用户。
 *
 * 只认常见的工具调用标记,而且标记后面要出现 Mika 的某个工具名(整词):回答里讲这些格式本身不算。
 * 只判断,不解析也不执行文字里的调用。
 */
internal object LeakedToolCall {
    /** 模型写工具调用时常用的标记。 */
    private val MARKERS = listOf(
        "<tool_call>", "<function=",
        "<|python_tag|>",
        "[TOOL_CALLS]",
        "<｜tool▁calls▁begin｜>", "<｜tool▁call▁begin｜>",
        "<|tool_calls_section_begin|>", "<|tool_call_begin|>",
        // gpt-oss(harmony 格式)
        "<|channel|>", "<|call|>", "to=functions.",
    )

    /**
     * [text] 里漏出了对 [toolNames] 中某个工具的调用时返回那个工具名;没有为 null。
     * 只看第一个调用标记之后出现的工具名,取最靠前的那个:标记前面的话里出现 wait、find 这类和工具同名的词不算。
     * 另外认以工具名为标签名的写法,比如 gpt-oss 写出的 `<ask_user reason="…">`。
     */
    fun find(text: String, toolNames: Collection<String>): String? {
        val names = toolNames.filter { it.isNotEmpty() }
        val start = MARKERS.mapNotNull { m -> text.indexOf(m).takeIf { it >= 0 } }.minOrNull()
        val marked = start?.let { s ->
            names.mapNotNull { name -> wholeWord(name).find(text, s)?.let { name to it.range.first } }
                .minByOrNull { it.second }?.first
        }
        return marked ?: names.mapNotNull { name -> tag(name).find(text)?.let { name to it.range.first } }
            .minByOrNull { it.second }?.first
    }

    private fun wholeWord(name: String) = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")

    /** `<工具名` 后面紧跟空白、`/` 或 `>`。 */
    private fun tag(name: String) = Regex("<\\s*" + Regex.escape(name) + "(?=[\\s/>])")
}
