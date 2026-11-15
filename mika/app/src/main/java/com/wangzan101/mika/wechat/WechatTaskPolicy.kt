// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.wechat

/** Channel transport has no trusted on-phone confirmation. Hybrid remote tasks start on their retained Own model. */
internal object WechatTaskPolicy {
    fun allowed(community: Boolean, hybrid: Boolean = false) = !community || hybrid
}
