// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Tool engine sees only policy decisions, never interface state or raw screen contents. */
interface CommunityBoundary {
    val appAccess: AppAccessPolicy get() = AppAccessPolicy.ALLOW
    suspend fun foregroundPackages(): List<String?>
    suspend fun hiddenScreen(): kotlinx.serialization.json.JsonObject?
}

/** Privacy denylist plus the user's current manual classifications and blocks.
 * Tadolio's public browser uses an isolated per-run WebView profile.
 */
object CommunityPolicy {
    const val ERROR = "community_app_not_allowed"
    const val HIDDEN = "屏幕已隐藏（社区模式）"
    const val MESSAGE = "社区模式无法访问隐私类 App，以免内容交给随机社区节点。请用自己的模型来做。"
    fun allowed(pkg: String?, state: AppAccessState = AppAccessState()): Boolean =
        state.available && !pkg.isNullOrBlank() && !CommunityPrivacy.blocked(pkg, state)
    fun ownsNode(windowId: Int, windowPackage: String?, nodeWindowId: Int, nodePackage: String?, state: AppAccessState = AppAccessState()): Boolean =
        windowId == nodeWindowId && allowed(windowPackage, state) && windowPackage == nodePackage
    fun visible(owners: List<String?>, state: AppAccessState = AppAccessState()): Boolean =
        owners.isNotEmpty() && owners.all { allowed(it, state) }
    fun requireTarget(pkg: String?, state: AppAccessState = AppAccessState()) { if (!allowed(pkg, state)) throw AppAccessDenied(ERROR, pkg) }
    fun requireForeground(owners: List<String?>, allowEmpty: Boolean = false, state: AppAccessState = AppAccessState()) {
        if (!(allowEmpty && owners.isEmpty()) && !visible(owners, state))
            throw AppAccessDenied(ERROR, owners.firstOrNull { !allowed(it, state) })
    }
    fun placeholder(pkg: String?) = buildJsonObject { put("message", HIDDEN); put("package", pkg) }
    fun hidden(s: ScreenSnapshot, pkg: String? = s.packageName) = ScreenSnapshot(
        s.snapshotId, pkg, emptyList(), s.width, s.height, protected = true,
        hasWindow = s.hasWindow, communityHidden = true)
    /** Content changes are harmless once every captured window and node has trusted ownership. */
    fun hiddenReasons(s: ScreenSnapshot, before: List<String?>, after: List<String?>, state: AppAccessState = AppAccessState()): List<String> = buildList {
        if (s.communityHidden) add("reader_rejected")
        if (!visible(before, state)) add("before_not_allowed_or_empty")
        if (!visible(after, state)) add("after_not_allowed_or_empty")
        if (before != after) add("owners_changed")
        if (!allowed(s.packageName, state)) add("snapshot_package_not_allowed")
        if (s.windows.any { !allowed(it.packageName, state) }) add("window_package_not_allowed")
    }
    fun sanitize(s: ScreenSnapshot, before: List<String?>, after: List<String?>, state: AppAccessState = AppAccessState()): ScreenSnapshot =
        if (hiddenReasons(s, before, after, state).isEmpty()) s
        else hidden(s, after.firstOrNull { !allowed(it, state) } ?: s.packageName ?: after.firstOrNull())
}
