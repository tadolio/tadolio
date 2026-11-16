// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import android.content.SharedPreferences

class NumberPreferences(private val prefs: SharedPreferences) {
    fun publicCard() = prefs.getString("publicCard", "").orEmpty()
    fun setPublicCard(value: String) {
        require(value.codePointCount(0, value.length) <= 1000)
        check(prefs.edit().putString("publicCard", value).commit())
    }
    fun autoReplyEnabled() = prefs.getBoolean("autoReply", true)
    fun governanceJuryEnabled() = prefs.getBoolean("governanceJuryEnabled", true)
    fun setGovernanceJuryEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean("governanceJuryEnabled", enabled).commit())
    }
    fun postStandbyEnabled() = prefs.getBoolean("postStandbyEnabled", true)
    fun setPostStandbyEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean("postStandbyEnabled", enabled).commit())
    }
    fun setAutoReplyEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean("autoReply", enabled).commit())
    }
}
