// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

object ReferralLink {
    fun operator(link: String?): String? = link?.let {
        Regex("(?:tadolio://operator/|https://tadolio.com/operator.html#)([A-Za-z0-9_-]{8,128})").matchEntire(it)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT)
    }
    fun operatorWeb(invite: String): String = "https://tadolio.com/operator.html#$invite"
    fun operatorDeep(invite: String): String = "tadolio://operator/$invite"

    fun number(link: String?): String? = link?.let {
        Regex("tadolio://invite/([0-9]{8})").matchEntire(it)?.groupValues?.get(1)
    }
}
