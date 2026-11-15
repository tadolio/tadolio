// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.*
import org.junit.Test

class HubTlsTest {
    private fun hostRoots() = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().single().acceptedIssuers
    }

    @Test fun userInstalledRootsAreExcludedFromHubTrust() {
        val certificates = hostRoots()
        val system = certificates[0]
        val user = certificates.first { it.subjectX500Principal != system.subjectX500Principal }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("system:root", system)
            setCertificateEntry("user:root", user)
            setCertificateEntry("unknown:root", user)
        }
        val trust = HubTls.systemTrust(store)
        assertEquals(listOf(system), trust.acceptedIssuers.toList())
        trust.checkServerTrusted(arrayOf(system), system.publicKey.algorithm)
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(user), user.publicKey.algorithm)
        }
    }

    @Test fun emptySystemStoreFailsClosed() {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("user:root", hostRoots()[0])
        }
        assertThrows(IllegalStateException::class.java) { HubTls.systemTrust(store) }
    }

    @Test fun bothHubTransportsUseSystemTrustWhileModelServersRetainUserCaSupport() {
        for (path in listOf("community/CommunityClient.kt", "family/FamilyClient.kt")) {
            val source = File("src/main/java/com/wangzan101/mika/$path").readText()
            assertTrue(path, source.contains("HubTls.builder()"))
            assertFalse(path, source.contains("OkHttpClient.Builder()"))
        }
        val config = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(config.contains("certificates src=\"user\""))
    }
}
