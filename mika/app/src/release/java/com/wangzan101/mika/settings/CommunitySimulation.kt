// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import android.content.Context
import androidx.compose.runtime.Composable
import com.wangzan101.mika.core.run.ComputeMode

/** Production never reads a simulator preference. Future real community providers must resolve mode
 * from trusted provider identity at TaskRun creation, never from synchronized settings or tool input. */
internal object CommunitySimulation {
    fun mode(context: Context): ComputeMode = ComputeMode.Own
    @Composable fun Controls() {}
}
