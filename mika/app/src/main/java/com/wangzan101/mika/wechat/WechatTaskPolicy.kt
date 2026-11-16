// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.wechat

/** Channel transport has no trusted on-phone confirmation. */
internal object WechatTaskPolicy {
    fun allowed(community: Boolean) = !community
}
