// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.about

import com.wangzan101.mika.R
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.i18n.tr
import java.net.URLEncoder

/** Mika 对外的地址都在这里:仓库、问题、检查更新用的两个来源。换地址只改这一处。 */
object MikaLinks {
    // Product links are configured here before distribution. Blank disables the corresponding entry.
    const val REPO = BuildConfig.REPO_URL
    const val X_URL = "https://x.com/tadolio_"
    const val X_HANDLE = "@tadolio_"

    fun cliInstallGuide(repo: String = REPO): String =
        if (repo.isBlank()) "" else "$repo/blob/main/docs/agent-quickstart.md"
    val MCP_GUIDE = if (REPO.isBlank()) "" else "$REPO/blob/main/docs/mcp.md"
    val REPO_LABEL = REPO.removePrefix("https://github.com/")
    val README = if (REPO.isEmpty()) "" else "$REPO#readme"
    val RELEASES = if (REPO.isEmpty()) "" else "$REPO/releases"
    val NEW_ISSUE = if (REPO.isEmpty()) "" else "$REPO/issues/new"
    val LATEST_RELEASE_API = if (REPO.isEmpty()) "" else "https://api.github.com/repos/$REPO_LABEL/releases/latest"
    // Upstream attribution is independent of product update and feedback destinations.
    const val UPSTREAM_REPO = "https://github.com/wangzan101/Mika"
    val updatesEnabled: Boolean get() = LATEST_RELEASE_API.isNotBlank() && RELEASES.isNotBlank() && !REPO.equals(UPSTREAM_REPO, ignoreCase = true)
    val feedbackEnabled: Boolean get() = NEW_ISSUE.isNotBlank()

    /** 反馈时预先填进问题模板的设备与版本信息。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 预先填好的问题正文:机型、安卓版本、Mika 版本三行,下面留空几段给用户写。 */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** 新建问题的地址,标题留空、正文预先填好;参数按 URL 编码(空格写成 %20)。 */
    fun issueUrl(d: Device): String = if (feedbackEnabled) "$NEW_ISSUE?title=&body=${encode(issueBody(d))}" else ""

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
