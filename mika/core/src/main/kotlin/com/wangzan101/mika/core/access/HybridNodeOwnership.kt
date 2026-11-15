// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

/** Capture-local audit: record identity failures without dropping the triggering raw node.
 * The platform must promote before caching/serializing the captured result. No content is retained.
 */
class HybridNodeOwnership {
    var trusted: Boolean = true
        private set

    fun observe(windowId: Int, windowPackage: String?, nodeWindowId: Int, nodePackage: String?) {
        if (!CommunityPolicy.ownsNode(windowId, windowPackage, nodeWindowId, nodePackage)) trusted = false
    }
}
