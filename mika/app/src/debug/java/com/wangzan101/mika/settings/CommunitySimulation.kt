// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.run.ComputeMode
import com.wangzan101.mika.ui.components.*

internal object CommunitySimulation {
    private const val PREFS = "debug_community_simulation"
    private const val ENABLED = "enabled"
    fun mode(context: Context): ComputeMode = if (BuildConfig.DEBUG &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)) ComputeMode.Community else ComputeMode.Own

    @Composable fun Controls() {
        if (!BuildConfig.DEBUG) return
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
        var on by remember { mutableStateOf(prefs.getBoolean(ENABLED, false)) }
        fun change(value: Boolean) { on = value; prefs.edit().putBoolean(ENABLED, value).apply() }
        GroupLabel("社区模式测试")
        GroupCard {
            ListRow(null, "模拟社区节点模式", "仅供开发测试，模型仍然走你自己的配置。仅影响新任务。", first = true,
                onClick = { change(!on) }) { MikaSwitch(on, { change(it) }) }
        }
    }
}
