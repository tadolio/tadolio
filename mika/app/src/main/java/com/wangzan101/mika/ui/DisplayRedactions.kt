// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

/** Presentation only: model payloads, stored text and approval digests stay unchanged. */
internal object DisplayRedactions {
    private val keys = mapOf("card" to R.string.hidden_card, "otp" to R.string.hidden_otp,
        "id" to R.string.hidden_id, "password" to R.string.hidden_password,
        "mnemonic" to R.string.hidden_mnemonic, "private_key" to R.string.hidden_private_key)
    fun localize(text: String, label: (String) -> String): String =
        Regex("\\[redacted:(card|otp|id|password|mnemonic|private_key)\\]").replace(text) { label(it.groupValues[1]) }
    fun forUi(text: String): String = localize(text) { tr(keys.getValue(it)) }
}
