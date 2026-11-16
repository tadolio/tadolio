// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.about

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MikaLinksTest {
    @get:Rule val texts = ChineseTexts()

    private val device = MikaLinks.Device("Google", "Pixel 8", "14", 34, "0.1.0", "65fcf92")

    @Test
    fun bodyHasThreePrefilledLines() {
        val lines = MikaLinks.issueBody(device).lines()
        assertEquals("- 机型：Google Pixel 8", lines[0])
        assertEquals("- 安卓版本：14（SDK 34）", lines[1])
        assertEquals("- Tadolio 版本：0.1.0（构建 65fcf92）", lines[2])
        assertTrue(lines.contains("### 遇到了什么问题"))
        assertTrue(lines.contains("### 做了哪几步"))
        assertTrue(lines.contains("### 期望是什么样"))
        // 诊断信息不自动带上:只提示用户去哪里复制
        assertTrue(lines.contains("### 诊断信息"))
        assertTrue(lines.contains("（可选）在 Tadolio「关于 › 反馈问题 › 复制诊断信息」复制后粘贴在这里"))
    }

    /** 型号里已经带厂商名时不重复写。 */
    @Test
    fun modelNotDoubled() {
        val body = MikaLinks.issueBody(device.copy(manufacturer = "samsung", model = "SAMSUNG SM-S9180"))
        assertTrue(body.startsWith("- 机型：SAMSUNG SM-S9180\n"))
    }

    @Test
    fun unconfiguredProductLinksAreDisabled() {
        if (com.wangzan101.mika.BuildConfig.REPO_URL.isNotEmpty()) {
            val repo = com.wangzan101.mika.BuildConfig.REPO_URL
            assertEquals(repo, MikaLinks.REPO)
            assertEquals(repo.removePrefix("https://github.com/"), MikaLinks.REPO_LABEL)
            assertEquals("$repo#readme", MikaLinks.README)
            assertEquals("$repo/releases", MikaLinks.RELEASES)
            assertEquals("$repo/issues/new", MikaLinks.NEW_ISSUE)
            assertEquals("https://api.github.com/repos/${MikaLinks.REPO_LABEL}/releases/latest", MikaLinks.LATEST_RELEASE_API)
            assertTrue(MikaLinks.updatesEnabled && MikaLinks.feedbackEnabled)
            assertTrue(MikaLinks.issueUrl(device).startsWith("$repo/issues/new?"))
            return
        }
        assertFalse(MikaLinks.updatesEnabled)
        assertFalse(MikaLinks.feedbackEnabled)
        assertEquals("", MikaLinks.issueUrl(device))
        assertEquals("", MikaLinks.REPO)
        assertEquals("", MikaLinks.RELEASES)
        assertEquals("https://github.com/wangzan101/Mika", MikaLinks.UPSTREAM_REPO)
    }

    @Test
    fun xAndInstallGuideHaveIndependentProductDestinations() {
        assertEquals("https://x.com/tadolio_", MikaLinks.X_URL)
        assertEquals("@tadolio_", MikaLinks.X_HANDLE)
        assertEquals("", MikaLinks.cliInstallGuide(""))
        assertEquals("https://github.com/tadolio/tadolio/blob/main/docs/agent-quickstart.md",
            MikaLinks.cliInstallGuide("https://github.com/tadolio/tadolio"))
        assertTrue(java.io.File("../docs/agent-quickstart.md").isFile)
    }

    @Test
    fun everyLanguageHasXAndConfiguredOrUnconfiguredInstallCopy() {
        val folders = java.io.File("src/main/res").listFiles()!!.filter {
            java.io.File(it, "strings_about.xml").isFile
        }
        assertEquals(11, folders.size)
        for (folder in folders) {
            val chat = java.io.File(folder, "strings_chat.xml").readText()
            val about = java.io.File(folder, "strings_about.xml").readText()
            val mcp = java.io.File(folder, "strings_mcp.xml").readText()
            assertTrue(folder.name, chat.contains("name=\"setup_follow_x\"") && chat.contains("@tadolio_"))
            assertTrue(folder.name, about.contains("name=\"about_follow_x\""))
            assertFalse(folder.name, chat.contains("chat_example_github_star"))
            assertFalse(folder.name, (chat + about + mcp).contains("github.com/wangzan101", ignoreCase = true))
            val prompt = Regex("<string name=\"mcp_cli_install_prompt\">(.*?)</string>").find(mcp)!!.groupValues[1]
            val fallback = Regex("<string name=\"mcp_cli_install_no_repo\">(.*?)</string>").find(mcp)!!.groupValues[1]
            assertTrue(folder.name, prompt.contains("%1\$s"))
            assertFalse(folder.name, fallback.contains("http") || fallback.contains("%1\$s"))
        }
    }
}
