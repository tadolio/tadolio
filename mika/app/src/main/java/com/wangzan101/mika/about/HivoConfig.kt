// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.about

import com.wangzan101.mika.BuildConfig

object HivoConfig {
    // Read by reflection: BuildConfig fields are Java compile-time constants, which kotlinc inlines
    // into this class. An incremental build after a change to -PhubUrl/-PpointsUrl would then keep
    // the stale (often empty) URL, so the hub features silently disappear.
    val HUB_URL: String by lazy { buildConfigString("HUB_URL") }
    val communityEnabled: Boolean get() = HUB_URL.isNotBlank()
    val POINTS_URL: String by lazy { buildConfigString("POINTS_URL") }
    val pointsEnabled: Boolean get() = POINTS_URL.isNotBlank()

    private fun buildConfigString(name: String): String =
        runCatching { BuildConfig::class.java.getField(name).get(null) as String }.getOrDefault("")
}
