// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.run.ComputeMode

/** Phone calls and external scripts have no per-run promotion to the retained Own model. */
internal object CommunityRestrictions {
    fun active(context: Context): Boolean = active(
        ProviderStore.get(context).configFor(ModelRole.Main)?.kind,
        CommunitySimulation.mode(context),
    )

    fun active(kind: ProviderConfig.Kind?, simulation: ComputeMode?): Boolean =
        kind == ProviderConfig.Kind.Community || simulation == ComputeMode.Community
}
