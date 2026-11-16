// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

internal object OperatorForm {
    const val DEFAULT_MAX_USERS = 20
    val presets = listOf(5_000_000L,20_000_000L,50_000_000L)
    fun valid(name:String, intro:String, contact:String, share:Int?, maxUsers:Int?, declared:Boolean) =
        declared && name.trim().length in 1..24 && intro.length <= 120 && contact.length <= 80 &&
            share != null && share in 0..100 && maxUsers != null && maxUsers > 0
}
