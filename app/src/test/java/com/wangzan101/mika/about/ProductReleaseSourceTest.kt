// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.about

import org.junit.Assert.*
import org.junit.Test

class ProductReleaseSourceTest {
    private val base = "https://github.com/tadolio/app/releases"
    private fun release(url: String) = ReleaseInfo("0.1.50", "Tadolio", "", null,
        "$base/tag/v0.1.50", url, 123L, "sha256:abc")

    @Test fun mirrorCannotSupplyUpstreamApkOrItsVerificationData() {
        val found = ProductReleaseSource.restrict(release("https://github.com/wangzan101/Mika/releases/download/v0.1.50/Mika.apk"), base)!!
        assertNull(found.apkUrl)
        assertNull(found.apkSize)
        assertNull(found.apkDigest)
        assertTrue(found.pageUrl.startsWith(base))
    }

    @Test fun acceptsOnlyTheSameConfiguredProductAttachment() {
        val url = "$base/download/v0.1.50/Tadolio.apk"
        assertEquals(url, ProductReleaseSource.restrict(release(url), base)!!.apkUrl)
        for (bad in listOf("https://other.invalid/Tadolio.apk", "$base-evil/download/v1/Tadolio.apk", "http://github.com/tadolio/app/releases/download/v1/a.apk")) {
            assertNull(ProductReleaseSource.restrict(release(bad), base)!!.apkUrl)
        }
        assertNull(ProductReleaseSource.restrict(release(url), ""))
        assertNull(ProductReleaseSource.restrict(release(url), MikaLinks.UPSTREAM_REPO + "/releases"))
    }
}
