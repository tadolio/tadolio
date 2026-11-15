// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** Hub identities and relay credentials use system roots even when model servers allow user CAs. */
internal object HubTls {
    private val trust by lazy {
        if (System.getProperty("java.vm.name") == "Dalvik") {
            systemTrust(KeyStore.getInstance("AndroidCAStore").apply { load(null) })
        } else {
            // Host-side JVM tests use the JDK's system trust store.
            trustManager(null)
        }
    }
    private val socketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
    }

    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder().sslSocketFactory(socketFactory, trust)

    internal fun systemTrust(androidStore: KeyStore): X509TrustManager {
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        for (alias in androidStore.aliases()) {
            if (alias.startsWith("system:")) roots.setCertificateEntry(alias, androidStore.getCertificate(alias))
        }
        check(roots.size() > 0) { "No system CA certificates available for hub communication" }
        return trustManager(roots)
    }

    private fun trustManager(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(store)
            trustManagers.filterIsInstance<X509TrustManager>().single()
        }
}
