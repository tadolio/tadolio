// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OptionalSandboxBoundaryTest {
    private fun source(path: String) = File("src/main/java/com/wangzan101/mika/$path").readText()

    @Test fun terminalMenuAndWorkspaceEntryRequireSandbox() {
        val menu = source("ui/ConversationMenu.kt")
        assertTrue(menu.contains("if (com.wangzan101.mika.BuildConfig.SANDBOX_ENABLED)"))
        assertTrue(menu.indexOf("BuildConfig.SANDBOX_ENABLED") < menu.indexOf("R.string.terminal_open"))
        assertTrue(source("ui/AdvancedSection.kt").contains("BuildConfig.SANDBOX_ENABLED && (w.conversationId != null || w.projectId != null)"))
        assertTrue(source("ui/SettingsScreen.kt").contains("BuildConfig.SANDBOX_ENABLED && it !is SandboxStatus.Unsupported"))
    }

    @Test fun restoredActivityLauncherAndTerminalRuntimeRequireSandbox() {
        val activity = source("ui/terminal/TerminalActivity.kt")
        assertTrue(activity.indexOf("if (!com.wangzan101.mika.BuildConfig.SANDBOX_ENABLED)") < activity.indexOf("setContent {"))
        val launcher = activity.substringAfter("object TerminalLauncher")
        assertTrue(launcher.indexOf("BuildConfig.SANDBOX_ENABLED") < launcher.indexOf("Intent(context"))
        val terminal = source("terminal/TerminalHub.kt")
        assertTrue(terminal.contains("sandboxEnabled: Boolean = com.wangzan101.mika.BuildConfig.SANDBOX_ENABLED"))
        val runtime = terminal.substringAfter("suspend fun openWorkspace(")
        assertTrue(runtime.indexOf("check(sandboxEnabled)") < runtime.indexOf("_sessions.value"))
    }

    @Test fun disabledSandboxExcludesNewRootfsAndNativePtyHostFromPackaging() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("if (!sandboxEnabled) excludes += setOf(\"**/libproot*.so\", \"**/libmikarun.so\")"))
        assertTrue(gradle.contains("ignoreAssetsPatterns += listOf(\"runtime\", \"sandbox\")"))
        val jni = File("src/main/jniLibs").walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertEquals(setOf("libproot.so", "libproot_loader.so", "libmikarun.so"), jni)
    }
}
