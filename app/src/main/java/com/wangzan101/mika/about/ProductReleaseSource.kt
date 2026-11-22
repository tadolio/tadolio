// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.about

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Mirror metadata may describe only an attachment of the configured product release. */
internal object ProductReleaseSource {
    fun allowedBase(releasesUrl: String): Boolean {
        val url = releasesUrl.toHttpUrlOrNull() ?: return false
        return !(url.host.equals("github.com", true) &&
            url.pathSegments.take(2).joinToString("/").equals("wangzan101/Mika", true))
    }

    fun restrict(release: ReleaseInfo, releasesUrl: String): ReleaseInfo? {
        if (!allowedBase(releasesUrl)) return null
        val base = releasesUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        val apk = release.apkUrl?.toHttpUrlOrNull()
        val attachment = apk?.takeIf {
            it.scheme == base.scheme && it.host == base.host && it.port == base.port &&
                it.username.isEmpty() && it.password.isEmpty() &&
                it.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/download/") &&
                it.encodedPath.endsWith(".apk", true) && allowedBase(it.toString())
        }?.toString()
        val tag = release.pageUrl.substringAfterLast("/tag/", "")
            .takeIf { it.matches(Regex("[A-Za-z0-9._+-]+")) } ?: "v${release.version}"
        return release.copy(
            pageUrl = "${releasesUrl.trimEnd('/')}/tag/$tag",
            apkUrl = attachment,
            apkSize = release.apkSize.takeIf { attachment != null },
            apkDigest = release.apkDigest.takeIf { attachment != null },
        )
    }
}
