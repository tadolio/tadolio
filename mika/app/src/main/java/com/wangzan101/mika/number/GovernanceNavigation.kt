// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

/** Only case identifiers are accepted from notification intents. */
internal object GovernanceNavigation {
    const val EXTRA_CASE = "hivo.governance.case"
    fun caseId(value: String?): String? = value?.takeIf {
        it.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
    }
}
