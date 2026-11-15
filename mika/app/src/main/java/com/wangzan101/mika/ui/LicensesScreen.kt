// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.tr

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 许可证页上第三方组件的数据;与仓库 licenses/THIRD_PARTY_NOTICES.md 的条目一一对应(LicensesTest 核对)。 */
object Licenses {
    data class Entry(val name: String, val license: String, val note: String)

    val GPL_SOURCE_OFFER: String get() =
        tr(R.string.hivo_license_0)

    val RUNTIME_NOTE: String get() =
        tr(R.string.hivo_license_1)

    /** 自带运行环境里的软件包(assets/runtime/packages.json,由 tools/sandbox/build-runtime.py 生成)。 */
    fun runtimePackages(json: String): List<Entry> =
        Json.parseToJsonElement(json).jsonObject.getValue("packages").jsonArray.map { e ->
            val o = e.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Entry(f("name"), f("license"), tr(R.string.hivo_license_version, f("version"), f("url")))
        }

    val ENTRIES: List<Entry> get() = listOf(
        Entry("Mika", "Apache-2.0", tr(R.string.hivo_license_2)),
        Entry("PRoot", "GPL-2.0-or-later", tr(R.string.hivo_license_3, GPL_SOURCE_OFFER)),
        Entry("talloc", "LGPL-3.0-or-later", tr(R.string.hivo_license_4)),
        Entry("Apache Commons Compress", "Apache-2.0", tr(R.string.hivo_license_5)),
        Entry("Apache Commons Codec", "Apache-2.0", tr(R.string.hivo_license_6)),
        Entry("Apache Commons IO", "Apache-2.0", tr(R.string.hivo_license_7)),
        Entry("Apache Commons Lang", "Apache-2.0", tr(R.string.hivo_license_8)),
        Entry(tr(R.string.hivo_license_9), "Apache-2.0", tr(R.string.hivo_license_10)),
        Entry("kotlinx.coroutines", "Apache-2.0", tr(R.string.hivo_license_11)),
        Entry("kotlinx.serialization", "Apache-2.0", tr(R.string.hivo_license_12)),
        Entry("OpenTelemetry", "Apache-2.0", tr(R.string.hivo_license_13)),
        Entry("Protocol Buffers", "BSD-3-Clause", tr(R.string.hivo_license_14)),
        Entry("OkHttp", "Apache-2.0", tr(R.string.hivo_license_15)),
        Entry("Okio", "Apache-2.0", tr(R.string.hivo_license_16)),
        Entry(tr(R.string.hivo_license_17), "Apache-2.0", tr(R.string.hivo_license_18)),
        Entry(tr(R.string.hivo_license_19), "Apache-2.0", "listenablefuture 1.0。https://github.com/google/guava"),
        Entry("scrcpy", "Apache-2.0", tr(R.string.hivo_license_20)),
        Entry("Alpine Linux", tr(R.string.hivo_license_21), RUNTIME_NOTE),
        Entry("XZ for Java", "0BSD", tr(R.string.hivo_license_22)),
        Entry("ZXing", "Apache-2.0", "3.5.3. https://github.com/zxing/zxing"),
        Entry("通话功能（calls/）实现", "Apache-2.0", "通话功能（拨号、接听、挂断、AI 接听、人工接管、PCM 音频桥、实时语音客户端）按 Apache-2.0 许可并入;实时语音经火山引擎（Volcengine）的 WebSocket 接口。"),
        Entry("xterm.js", "MIT", "版本 5.5.0(及 @xterm/addon-fit 0.10.0),用户终端页的终端仿真器。https://github.com/xtermjs/xterm.js"),
        Entry("commonmark-java", "BSD-2-Clause", tr(R.string.hivo_license_23)),
    ).filter { com.wangzan101.mika.BuildConfig.SANDBOX_ENABLED || it.name !in setOf("PRoot", "talloc", "Alpine Linux") }
}

/**
 * 许可证页(关于页底部「Apache-2.0 开源许可」进来):先是许可证原文(assets/licenses/Apache-2.0.txt),
 * 下面列出随 Mika 发布的第三方组件,一个组件一行(名称、许可证),说明在行下面;页脚指向完整文本。
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenseText = remember {
        runCatching { context.assets.open(LICENSE_ASSET).use { it.readBytes().decodeToString() } }.getOrDefault("")
    }
    val runtime = remember {
        if (!com.wangzan101.mika.BuildConfig.SANDBOX_ENABLED) emptyList() else
        runCatching { context.assets.open("runtime/packages.json").use { Licenses.runtimePackages(it.readBytes().decodeToString()) } }
            .getOrDefault(emptyList())
    }
    val thirdParty = Licenses.ENTRIES.filter { it.name != "Mika" } + runtime
    SubPage(AboutCopy.LICENSE_TITLE, onBack) {
        if (licenseText.isNotEmpty()) {
            MikaCard(Modifier.padding(bottom = 12.dp).testTag("license-text")) {
                Text(
                    licenseText.trimEnd(), Modifier.padding(16.dp),
                    style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 19.sp),
                    color = MikaTheme.colors.ink2,
                )
            }
        }
        GroupLabel(stringResource(R.string.licenses_third_party, thirdParty.size))
        GroupCard {
            thirdParty.forEachIndexed { i, e ->
                Column {
                    ListRow(null, e.name, e.license, first = i == 0)
                    Text(e.note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
                }
            }
        }
        FooterNote(stringResource(R.string.licenses_footer))
    }
}

private const val LICENSE_ASSET = "licenses/Apache-2.0.txt"
