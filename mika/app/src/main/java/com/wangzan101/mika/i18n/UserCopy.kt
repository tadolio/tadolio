// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.i18n

import com.wangzan101.mika.R

/** Render legacy machine markers for people; never use this in model input or stored records. */
fun userCopy(text: String): String = when (text) {
        "正在核实结果" -> tr(R.string.hivo_note_verifying)
        "按以往的做法操作" -> tr(R.string.hivo_note_replay)
        "回答的依据在读到的界面、网页或命令输出里都找不到" -> tr(R.string.hivo_note_not_in_reads)
        "核查器没有逐条核对每一条标准" -> tr(R.string.hivo_note_criteria)
        "核查器认为没有做成,但没有指出哪条标准没满足" -> tr(R.string.hivo_note_no_reason)
        "这是接着上文的追问,没能列出具体标准,核查判不准" -> tr(R.string.hivo_note_follow_up)
        "本次任务没有打开 App,也没有读到任何材料" -> tr(R.string.hivo_note_nothing_read)
        "回答依据的是屏上被截断的文字(以…结尾),只看到了一部分,没能完整核实" -> tr(R.string.hivo_note_truncated)
        "结果页受系统防截屏保护,看不到画面,没法核实" -> tr(R.string.hivo_note_protected)
        "核查超时" -> tr(R.string.hivo_note_timeout)
        "核查器的回复无法解析" -> tr(R.string.hivo_note_unparsed)
        "条件检查没有返回可读取的结果" -> tr(R.string.hivo_note_condition)
        "条件检查结果缺少 met 状态" -> tr(R.string.hivo_note_condition_met)
        "已取消" -> tr(R.string.hivo_note_cancelled)
        "任务异常中断" -> tr(R.string.hivo_note_interrupted)
    else -> when {
        text.startsWith("核查器出错:") -> tr(R.string.hivo_note_error, text.removePrefix("核查器出错:"))
        text.startsWith("未确认:") -> tr(R.string.hivo_note_unconfirmed, text.removePrefix("未确认:"))
        else -> text
    }
}
