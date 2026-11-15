// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community
import java.io.File
import java.nio.file.Files
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
class OperatorHubTest {
    private val cipher=object:KeyCipher {override fun encrypt(plain:ByteArray)=plain;override fun decrypt(data:ByteArray)=data}
    @Test fun twoIdentitiesReuseRegistrationAndPublishCardAndResetInvite() {
        val dir=Files.createTempDirectory("t55-").toFile()
        val hub=MockWebServer();hub.start()
        try {
            fun store(kind:String)=MinerStore(File(dir,kind),cipher,MemoryPrefs()).also { it.save(buildJsonObject {
                put("kind",kind);put("base","https://api.example.com/v1");put("model","test");put("key","test-only");put("limit",5000000);put("metered",true)
                put("name","Family");put("intro","");put("contact","");put("share",0);put("maxUsers",20);put("number","12345678")
            }) }
            val miner=store("compute");val operator=store("operator")
            fun worker(s:MinerStore)=MinerWorker(null,s,hub.url("/").toString()){true}
            for((index,s) in listOf(miner,operator).withIndex()) {
                hub.enqueue(MockResponse().setBody("""{"nodeId":"node$index","token":"test-token"}"""))
                worker(s).identity()
                val request=hub.takeRequest();val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(if(index==0) "compute" else "operator",body["descriptor"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
                CommunityCrypto.verifyDescriptor(body["descriptor"]!!.jsonObject,body["signature"]!!.jsonPrimitive.content)
            }
            assertNotEquals(miner.read()["descriptor"],operator.read()["descriptor"])
            worker(miner).identity();worker(operator).identity();assertEquals(2,hub.requestCount)
            hub.enqueue(MockResponse().setBody("{}"));worker(operator).submitCard()
            val card=Json.parseToJsonElement(hub.takeRequest().body.readUtf8()).jsonObject
            assertEquals(20,card["maxUsers"]!!.jsonPrimitive.int);assertEquals(0,card["share"]!!.jsonPrimitive.int)
            for(reset in listOf(false,true)) {
                hub.enqueue(MockResponse().setBody("""{"invite":"ABCDEFG234"}"""))
                assertEquals("ABCDEFG234",worker(operator).invite(reset))
                val req=hub.takeRequest();assertEquals("/v1/node/invite",req.path)
                assertEquals(reset,Json.parseToJsonElement(req.body.readUtf8()).jsonObject["reset"]?.jsonPrimitive?.boolean ?: false)
            }
            assertEquals("ABCDEFG234",operator.read()["invite"]!!.jsonPrimitive.content)
        } finally {hub.shutdown();dir.deleteRecursively()}
    }
}
