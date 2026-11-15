// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiTreeNode

/**
 * Local capture admission for a hybrid run. The reader must supply the unredacted Own snapshot;
 * never wrap CommunityScreenPort or retry an action to obtain a different result.
 * This helper does not grant action approval or replace Android's node ownership checks.
 */
class HybridScreenBoundary(
    private val compute: HybridCompute,
    private val appAccess: AppAccessPolicy = AppAccessPolicy.ALLOW,
    private val step: () -> Int,
) {
    val community: Boolean get() = compute.upgrade == null

    private suspend fun promote(reason: HybridReason) { compute.upgrade(step(), reason) }

    /** Pre-action checks only. Never catch a failed action and execute it again after promotion. */
    suspend fun beforeTarget(packageName: String?) {
        if (community && !CommunityPolicy.allowed(packageName, appAccess.current())) promote(HybridReason.AppNotAllowed)
    }

    suspend fun beforeForeground(owners: List<String?>, allowEmpty: Boolean = false) {
        if (community && !(allowEmpty && owners.isEmpty()) && !CommunityPolicy.visible(owners, appAccess.current()))
            promote(HybridReason.AppNotAllowed)
    }

    suspend fun beforeInput(text: String, context: String) {
        if (community && CommunitySensitive.redact(text, context) != text)
            promote(HybridReason.SensitiveContent)
    }

    /** Capabilities which pure community intentionally suppresses require Own before acquisition. */
    suspend fun requireOwn(reason: HybridReason = HybridReason.WindowHidden) {
        if (community) promote(reason)
    }

    /** Before invoking a platform reader which would otherwise reject these window owners. */
    suspend fun beforeRead(owners: List<String?>) {
        if (compute.upgrade == null && !CommunityPolicy.visible(owners, appAccess.current()))
            promote(HybridReason.AppNotAllowed)
    }

    /** Inspect the captured object before any masking, caching, recording or model serialization. */
    suspend fun captured(
        raw: ScreenSnapshot, before: List<String?>, after: List<String?>, trustedOwnership: Boolean = true,
    ): ScreenSnapshot {
        // A hidden result has already lost information. Do not pass it off as a seamless handoff.
        check(!raw.communityHidden) { "hybrid_requires_raw_snapshot" }
        if (compute.upgrade != null) return raw
        val reason = when {
            !CommunityPolicy.allowed(raw.packageName, appAccess.current()) || !CommunityPolicy.visible(before, appAccess.current()) ||
                !CommunityPolicy.visible(after, appAccess.current()) -> HybridReason.AppNotAllowed
            !trustedOwnership || raw.protected || raw.unreadableApp != null ||
                CommunityPolicy.hiddenReasons(raw, before, after, appAccess.current()).isNotEmpty() -> HybridReason.WindowHidden
            raw.elements.any { it.secret } || raw.windows.any { w -> w.nodes.any(::secret) } -> HybridReason.PasswordField
            sensitive(raw) -> HybridReason.SensitiveContent
            else -> null
        }
        if (reason != null) promote(reason)
        return raw
    }

    /** Read exactly once: promotion never replays a preceding UI action or substitutes masked history. */
    suspend fun capture(owners: suspend () -> List<String?>, read: suspend () -> ScreenSnapshot): ScreenSnapshot {
        val before = owners()
        beforeRead(before)
        val raw = read()
        return captured(raw, before, owners())
    }

    private fun secret(node: UiTreeNode): Boolean = node.secret || node.children.any(::secret)

    private fun sensitive(raw: ScreenSnapshot): Boolean {
        val texts = buildList {
            addAll(raw.elements.flatMap { listOfNotNull(it.name, it.placeholder) })
            fun visit(node: UiTreeNode) {
                addAll(listOfNotNull(node.text, node.description, node.hintText))
                node.children.forEach(::visit)
            }
            raw.windows.forEach { w -> w.title?.let { add(it) }; w.nodes.forEach(::visit) }
        }
        // The tree and flat element list may repeat the same mnemonic. Check each leaf as well
        // as adjacency, so repetition cannot turn a valid 12/24-word run into an unrecognized one.
        return texts.any { CommunitySensitive.sensitive(it) } || CommunitySensitive.sensitive(texts.joinToString("\n"))
    }
}
