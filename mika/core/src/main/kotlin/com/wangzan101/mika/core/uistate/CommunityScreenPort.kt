// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.access.CommunityPolicy
import com.wangzan101.mika.core.access.AppAccessDenied

/** Per-run boundary. Reads are sanitized after acquisition and before any serialization. */
class CommunityScreenPort(
    private val inner: ScreenPort,
    private val hybrid: com.wangzan101.mika.core.access.HybridScreenBoundary? = null,
) : ScreenPort, com.wangzan101.mika.core.access.CommunityBoundary {
    override val appAccess get() = inner.appAccess
    override suspend fun checkAppAccess() {
        if (hybrid != null) foreground(allowEmpty = true)
        inner.checkAppAccess()
    }
    override suspend fun foregroundPackages() = inner.foregroundPackages()
    override suspend fun hiddenScreen() = peek().let { if (it.communityHidden) CommunityPolicy.placeholder(it.packageName) else null }
    private suspend fun observed(read: suspend () -> ScreenSnapshot): ScreenSnapshot {
        hybrid?.let { return it.capture(inner::foregroundPackages, read) }
        val before = inner.foregroundPackages()
        val snap = try { read() } catch (e: AppAccessDenied) {
            return CommunityPolicy.hidden(ScreenSnapshot("community-hidden", inner.foregroundPackages().firstOrNull(), emptyList(), 0, 0))
        }
        return CommunityPolicy.sanitize(snap, before, inner.foregroundPackages(), appAccess.current())
    }
    private suspend fun <T> checked(body: suspend () -> T): T {
        foreground()
        val value = body()
        foreground()
        return value
    }
    private suspend fun target(pkg: String?) {
        hybrid?.beforeTarget(pkg)
        if (hybrid?.community != false) CommunityPolicy.requireTarget(pkg, appAccess.current())
    }
    private suspend fun foreground(allowEmpty: Boolean = false) {
        val owners = inner.foregroundPackages()
        hybrid?.beforeForeground(owners, allowEmpty)
        if (hybrid?.community != false) CommunityPolicy.requireForeground(owners, allowEmpty, appAccess.current())
    }
    private suspend fun input(text: String) {
        val raw = if (hybrid == null) inner.peek() else peek()
        val context = raw.elements.joinToString("\n") { it.name }
        hybrid?.beforeInput(text, context)
        if (hybrid?.community != false)
            com.wangzan101.mika.core.access.CommunitySensitive.requireInput(text, context)
    }
    private fun denied(): Nothing = throw AppAccessDenied(CommunityPolicy.ERROR, null)
    private suspend fun checkOpening() = foreground(allowEmpty = true)
    // ---------- 读屏 ----------
    override suspend fun snapshot() = observed { inner.snapshot() }
    override suspend fun peek() = observed { inner.peek() }
    override suspend fun screenshotPng(): ByteArray? {
        if (hybrid == null) return null
        hybrid.requireOwn(com.wangzan101.mika.core.access.HybridReason.Image)
        return inner.screenshotPng()
    }
    override suspend fun readVisibleText(): List<VisibleTextLine>? {
        if (hybrid == null) return null
        hybrid.requireOwn()
        return inner.readVisibleText()
    }
    // Local action-effect probing is optional and never model evidence. Keep it absent during
    // community execution instead of promoting every harmless tap merely to collect a probe.
    override suspend fun frameGrid(): com.wangzan101.mika.core.visual.FrameGrid? =
        if (hybrid?.community == false) inner.frameGrid() else null
    override suspend fun isProtected() = if (hybrid?.community == false) inner.isProtected() else true
    override suspend fun checkScreen(): ScreenCheck? {
        if (hybrid == null) return null
        hybrid.requireOwn()
        return inner.checkScreen()
    }

    // ---------- 在当前屏上的动作 ----------
    override suspend fun tapRef(ref: String, snapshotId: String) = checked { inner.tapRef(ref, snapshotId) }
    override suspend fun tapXY(x: Int, y: Int) = checked { inner.tapXY(x, y) }
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = checked { inner.doubleTapRef(ref, snapshotId) }
    override suspend fun doubleTapXY(x: Int, y: Int) = checked { inner.doubleTapXY(x, y) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = checked { input(text); inner.typeText(ref, snapshotId, text) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = checked { inner.clearText(ref, snapshotId) }
    override suspend fun typeAt(x: Int, y: Int, text: String) = checked { input(text); inner.typeAt(x, y, text) }
    override suspend fun clearAt(x: Int, y: Int) = checked { inner.clearAt(x, y) }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = checked { inner.scroll(ref, snapshotId, down) }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = checked { inner.swipe(x1, y1, x2, y2, durationMs) }
    override suspend fun idleScroll(fraction: Double) = checked { inner.idleScroll(fraction) }
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = checked { inner.gesture(paths, durationMs) }
    override suspend fun back() = checked { inner.back() }
    override suspend fun pressKey(keyCode: Int) = checked { inner.pressKey(keyCode) }
    override suspend fun setWheel(ref: String, snapshotId: String, value: String) = checked { inner.setWheel(ref, snapshotId, value) }

    // ---------- 等待与事件 ----------
    override suspend fun waitStable(timeoutMs: Long) = checked { inner.waitStable(timeoutMs) }
    override suspend fun awaitChange(timeoutMs: Long) = checked { inner.awaitChange(timeoutMs) }
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = checked { inner.waitFor(condition, value, timeoutMs) }
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? {
        if (hybrid == null) return null
        hybrid.requireOwn()
        return inner.nextEvent(kind, packageName, timeoutMs)
    }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> {
        if (hybrid == null) return emptyList()
        hybrid.requireOwn()
        return inner.watchEvents(kind, packageName, durationMs, maxEvents)
    }

    // ---------- Target and current foreground are both checked before opening ----------
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
        target(packageName)
        checkOpening(); return inner.openApp(packageName, cold)
    }

    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult {
        target(packageName)
        checkOpening(); return inner.openAppCopy(packageName, userId, cold)
    }

    override suspend fun shareText(packageName: String, text: String): ActionResult {
        target(packageName)
        checkOpening(); return inner.shareText(packageName, text)
    }

    /** 指定了 App 的在这里看;没指定的由端口按名单挑接手的 App(由谁打开只有端口知道)。 */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult {
        target(packageName)
        checkOpening()
        return inner.openLink(action, uri, packageName)
    }

    /** 只等占用登记、不读屏;目标照打开时一样先看,受限的 App 不必等,当场拒。 */
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        target(packageName)
        return inner.awaitAppFree(packageName, userId, timeoutMs)
    }

    /** 接手的 App 由端口按名单挑(由谁接只有端口知道);做完后的前台由执行入口照常核对。 */
    override suspend fun systemAction(action: SystemAction): SystemActionResult { hybrid?.requireOwn(); checkOpening(); return inner.systemAction(action) }

    // ---------- 不碰任何 App 界面的:原样转交 ----------
    override suspend fun systemCommand(command: String): SystemCommandResult? {
        if (hybrid == null) denied()
        hybrid.requireOwn()
        return inner.systemCommand(command)
    }
    override suspend fun listApps() = inner.listApps().let { apps -> if (hybrid?.community == false) apps else apps.filter { CommunityPolicy.allowed(it.first, appAccess.current()) } }
    override suspend fun otherProfiles(): Map<String, List<String>> {
        if (hybrid == null) return emptyMap()
        hybrid.requireOwn()
        return inner.otherProfiles()
    }
    override suspend fun appCopies(): List<AppCopy> {
        if (hybrid == null) return emptyList()
        hybrid.requireOwn()
        return inner.appCopies()
    }
    override val afterTask: AfterTaskMarks? get() = inner.afterTask

}
