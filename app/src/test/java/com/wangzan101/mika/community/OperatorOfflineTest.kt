// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community
import java.nio.file.Files
import com.wangzan101.mika.settings.KeyCipher
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class OperatorOfflineTest {
    @Test fun fallbackRequiresConsentAndDailyLimitDoesNotAsk() {
        for((accept,error) in listOf(false to "node_unavailable",true to "node_unavailable",true to "operator_daily_limit")) {
            val hub=MockWebServer();hub.start();val dir=Files.createTempDirectory("t55-offline").toFile();var asked=0
            try {
                val cipher=object:KeyCipher{override fun encrypt(plain:ByteArray)=plain;override fun decrypt(data:ByteArray)=data}
                val client=CommunityClient(hub.url("/").toString(),dir,cipher,true){asked++;accept}
                hub.enqueue(MockResponse().setBody("""{"clientId":"test","token":"test"}"""))
                hub.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"$error"}"""))
                if(accept && error=="node_unavailable")hub.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"node_unavailable"}"""))
                assertTrue(runCatching{client.open("operator")}.isFailure)
                hub.takeRequest()
                assertEquals("operator",Json.parseToJsonElement(hub.takeRequest().body.readUtf8()).jsonObject["kind"]!!.jsonPrimitive.content)
                assertEquals(if(error=="node_unavailable")1 else 0,asked)
                if(accept && error=="node_unavailable")assertEquals("compute",Json.parseToJsonElement(hub.takeRequest().body.readUtf8()).jsonObject["kind"]!!.jsonPrimitive.content)
                assertEquals(if(accept && error=="node_unavailable")3 else 2,hub.requestCount)
            } finally {hub.shutdown();dir.deleteRecursively()}
        }
    }
}
