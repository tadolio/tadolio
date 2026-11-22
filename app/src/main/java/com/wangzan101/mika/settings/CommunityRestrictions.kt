// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.run.ComputeMode

/** Compatibility for older callers; compute mode does not restrict capabilities. */
internal object CommunityRestrictions {
    fun active(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false
    fun active(@Suppress("UNUSED_PARAMETER") kind: ProviderConfig.Kind?, @Suppress("UNUSED_PARAMETER") simulation: ComputeMode?): Boolean = false
}
