// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.action

enum class FamilyMode { Private, Notify, Approve }
enum class FamilyCategory { Payment, Transfer, Verification, Install, Security, ScamSms;
    val wire: String get() = name.lowercase()
    companion object {
        fun fromWire(value: String): FamilyCategory? = when (value) {
            "pay", "order", "payment" -> Payment
            "transfer" -> Transfer
            "verification" -> Verification
            "install" -> Install
            "security" -> Security
            "scamsms" -> ScamSms
            else -> null
        }
    }
}

/** Immutable run snapshot. No peer message can write local policy. */
data class FamilyPolicy(private val choices: Map<FamilyCategory, FamilyMode> = emptyMap()) {
    fun mode(category: String): FamilyMode = FamilyCategory.fromWire(category)?.let { choices[it] } ?: FamilyMode.Private
    // SMS review is an event subscription, not an agent action approval policy.
    val active: Boolean get() = choices.any { (category, mode) -> category != FamilyCategory.ScamSms && mode != FamilyMode.Private }
    fun changed(category: FamilyCategory, mode: FamilyMode) = FamilyPolicy(choices + (category to mode))
    fun lowered(category: FamilyCategory, mode: FamilyMode) = mode(category.wire) == FamilyMode.Approve && mode != FamilyMode.Approve
}
