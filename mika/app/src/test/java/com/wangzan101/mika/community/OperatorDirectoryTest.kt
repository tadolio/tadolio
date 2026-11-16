// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.number.ReferralLink
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.ui.operatorFlag
import com.wangzan101.mika.ui.operatorText
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

class OperatorDirectoryTest {
    @Test fun preservesBindingErrorCodesForPreviewAndConsent() {
        val server = MockWebServer()
        server.start()
        val dir = Files.createTempDirectory("t54-errors").toFile()
        try {
            val client = CommunityClient(server.url("/").toString(), dir, cipher, true)
            server.enqueue(MockResponse().setBody("""{"clientId":"client","token":"synthetic"}"""))
            for ((status, code) in listOf(404 to "invite_not_found", 409 to "operator_full", 503 to "operator_unavailable")) {
                for (bind in listOf(false, true)) {
                    server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"$code"}"""))
                    try {
                        if (bind) client.bind("node") else client.preview("CODE")
                        fail("Expected binding failure")
                    } catch (e: IOException) {
                        assertEquals("community_$code", e.message)
                    }
                }
            }
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    private val cipher=object:KeyCipher { override fun encrypt(plain:ByteArray)=plain;override fun decrypt(data:ByteArray)=data }
    @Test fun mapsPublicDirectoryAndEmptyPagesWithoutClientRegistration() {
        val server=MockWebServer();server.start()
        val dir=Files.createTempDirectory("t53-directory").toFile()
        try {
            val client=CommunityClient(server.url("/").toString(),dir,cipher,true)
            server.enqueue(MockResponse().setBody("""{"items":[{"nodeId":"node","name":"Friends","share":100,"full":true,"official":true,"online":true,"users":"10+"}],"cursor":"50"}"""))
            val page=client.operators();val card=page.getValue("items").jsonArray.single().jsonObject
            assertEquals("Friends",card.operatorText("name"));assertTrue(card.operatorFlag("full"));assertTrue(card.operatorFlag("official"))
            assertEquals("/v1/public/operators",server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"items":[],"cursor":null}"""))
            assertTrue(client.operators("50").getValue("items").jsonArray.isEmpty())
            assertEquals("/v1/public/operators?cursor=50",server.takeRequest().path)
            assertEquals(0,dir.listFiles()!!.size)
        } finally {server.shutdown();dir.deleteRecursively()}
    }
    @Test fun invitePreviewConsentAndReportUseAuthenticatedRoutes() {
        val server=MockWebServer();server.start();val dir=Files.createTempDirectory("t53-consent").toFile()
        try {
            val client=CommunityClient(server.url("/").toString(),dir,cipher,true)
            server.enqueue(MockResponse().setBody("""{"clientId":"client","token":"synthetic"}"""))
            server.enqueue(MockResponse().setBody("""{"nodeId":"node","card":{"name":"Friends","share":100,"contact":"DM"}}"""))
            assertEquals("DM",client.preview("ABCDEFG234")["card"]!!.jsonObject.operatorText("contact"))
            assertEquals("/v1/client/register",server.takeRequest().path)
            assertEquals("ABCDEFG234",Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.operatorText("invite"))
            server.enqueue(MockResponse().setBody("{}"));client.bind("node",100)
            val consent=server.takeRequest();assertEquals("Bearer synthetic",consent.getHeader("Authorization"))
            assertEquals(100,Json.parseToJsonElement(consent.body.readUtf8()).jsonObject["share"]!!.jsonPrimitive.int)
            server.enqueue(MockResponse().setBody("{}"));client.report("node","Reason")
            assertEquals("/v1/operators/node/report",server.takeRequest().path)
        } finally {server.shutdown();dir.deleteRecursively()}
    }
    @Test fun providerAllowanceUsesExplicitErrorCode() {
        val server=MockWebServer();server.start();val dir=Files.createTempDirectory("t53-paused").toFile()
        try {
            server.enqueue(MockResponse().setBody("""{"clientId":"client","token":"synthetic"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"operator_daily_limit"}"""))
            val error=assertThrows(IOException::class.java) {CommunityClient(server.url("/").toString(),dir,cipher,true).open("operator")}
            assertEquals("community_operator_daily_limit",error.message)
        } finally {server.shutdown();dir.deleteRecursively()}
    }
    @Test fun linksAndCopyFallBackToEnglishAndPageRequiresConsent() {
        assertEquals("ABCDEFG234",ReferralLink.operator(ReferralLink.operatorDeep("ABCDEFG234")))
        assertEquals("ABCDEFG234",ReferralLink.operator(ReferralLink.operatorWeb("ABCDEFG234")))
        assertNull(ReferralLink.operator("https://example.com/operator.html#ABCDEFG234"))
        assertNull(ReferralLink.operator("tadolio://operator/<script>"))
        val english=ResFileTexts.english.string(R.string.operator_explanation,emptyArray())
        assertEquals(english,ResFileTexts("values-ja",Locale.JAPANESE).string(R.string.operator_explanation,emptyArray()))
        val source=File("src/main/java/com/wangzan101/mika/ui/OperatorDirectory.kt").readText()
        for(term in listOf("!item.operatorFlag(\"full\")","community_binding_contact","operator_share_rule","operator_report","client.bind","operator_empty"))assertTrue(source.contains(term))
        val controls=File("src/main/java/com/wangzan101/mika/ui/CommunityControls.kt").readText()
        val onboarding = controls.substringAfter("if (onboarding) {").substringBefore("        return\n    }")
        assertTrue(onboarding.contains("R.string.operator_invite"))
        assertTrue(source.indexOf("R.string.community_binding_contact") < source.indexOf("R.string.operator_use)"))
        assertTrue(source.indexOf("R.string.t60_provider_consent") < source.indexOf("R.string.operator_use)"))
        val number=File("src/main/java/com/wangzan101/mika/ui/NumberScreen.kt").readText()
        assertTrue(number.contains("operatorText(\"kind\") == \"operator\""))
        assertTrue(ResFileTexts.chinese.string(R.string.operator_explanation,emptyArray()).contains("随时能解绑或拉黑"))
    }
}
