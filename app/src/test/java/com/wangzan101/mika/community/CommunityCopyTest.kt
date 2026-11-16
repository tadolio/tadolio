// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class CommunityCopyTest {
    @Test fun cloudAndNumberPrivacyCopyExistsInEverySupportedLanguage() {
        val languages = listOf("values", "values-zh", "values-b+zh+Hant", "values-ja", "values-ko",
            "values-es", "values-pt", "values-in", "values-ar", "values-ru")
        for (language in languages) {
            val values = mutableMapOf<String, String>()
            for (name in listOf("community.xml", "strings.xml")) {
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(File("src/main/res/$language/$name"))
                val strings = document.getElementsByTagName("string")
                for (i in 0 until strings.length) {
                    val node = strings.item(i)
                    values[node.attributes.getNamedItem("name").nodeValue] = node.textContent
                }
            }
            for (key in listOf("community_cloud_miner", "community_local_source", "community_cloud_privacy",
                    "number_community_peer", "number_community_owner", "hivo_community_unavailable")) {
                val text = checkNotNull(values[key]) { "$language/$key" }
                assertTrue("$language/$key", text.isNotBlank())
                assertFalse("$language/$key", text.contains("???") || text.contains('\uFFFD'))
            }
            assertTrue(values.getValue("community_cloud_miner").contains("%1\$s"))
            assertFalse(values.getValue("hivo_community_unavailable").contains("Base URL"))
        }
    }
}
