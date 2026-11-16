// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.number

/** The label is authenticated hub metadata. Publisher text never participates in this decision. */
object OfficialNumber {

    fun display(number: String, official: String?, language: String = java.util.Locale.getDefault().language): String {
        val formatted = if (number.matches(Regex("[0-9]{8}"))) number.take(4) + " " + number.drop(4) else number
        if (official.isNullOrBlank()) return formatted
        val badge = if (language == "zh") "官方" else "Official"
        return "$badge · $formatted"
    }
}
