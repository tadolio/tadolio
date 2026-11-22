// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.wechat

/** Capability availability is identical for all compute modes; confirmations remain on the phone. */
internal object WechatTaskPolicy {
    fun allowed(@Suppress("UNUSED_PARAMETER") community: Boolean) = true
}
