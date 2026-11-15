// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import java.io.File
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.*
import org.junit.Test

class MinerTest {
    @Test fun acceptsMeteredApiOnlyWithExplicitBudgetAndConsent(){
        for(base in listOf("https://openrouter.ai/api/v1","https://api.siliconflow.cn/v1","https://api.openai.com/v1")){
            assertTrue(MinerPolicy.validApi(base,"test-model","test-only-key",200000,true))
            assertFalse(MinerPolicy.validApi(base,"test-model","test-only-key",null,true))
            assertFalse(MinerPolicy.validApi(base,"test-model","test-only-key",0,true))
            assertFalse(MinerPolicy.validApi(base,"test-model","test-only-key",200000,false))
        }
        assertEquals(2000000L,MinerPolicy.DEFAULT_DAILY_TOKENS)
        assertFalse(MinerPolicy.DEFAULT_WIFI_ONLY)
        assertFalse(MinerPolicy.DEFAULT_CHARGING_ONLY)
        assertTrue(MinerPolicy.DEFAULT_AUTO_START)
    }
    @Test fun refusesSubscriptionLoginsAndUnsafeEndpoints(){
        for(base in listOf("https://chatgpt.com/backend-api","https://chat.openai.com","https://grok.com",
            "https://accounts.grok.com","https://api.chatgpt.com","http://api.example.com","https://u:p@example.com/v1","https://example.com/v1?key=test"))
            assertFalse(base,MinerPolicy.validApi(base,"model","test-only-key",200000,true))
    }
    @Test fun pausesOnPowerNetworkBatteryOrDailyLimitAndAllowsExplicitOverrides(){
        fun allowed(online:Boolean=true,wifi:Boolean=true,charging:Boolean=true,battery:Int=80,
                    wifiOnly:Boolean=true,chargingOnly:Boolean=true,used:Long=0)=
            MinerPolicy.canAccept(online,wifi,charging,battery,wifiOnly,chargingOnly,used,200000)
        assertTrue(allowed());assertFalse(allowed(online=false));assertFalse(allowed(wifi=false))
        assertFalse(allowed(charging=false));assertTrue(allowed(battery=20));assertFalse(allowed(used=200000))
        assertTrue(allowed(wifi=false,wifiOnly=false));assertTrue(allowed(charging=false,chargingOnly=false))
        assertFalse(allowed(online=false,wifiOnly=false,chargingOnly=false));assertFalse(allowed(battery=10,charging=false,chargingOnly=false))
        assertTrue(allowed(battery=10))
        assertFalse(MinerPolicy.canAccept(true,true,false,29,false,false,0,2000000,30))
    }
    @Test fun estimatesPricesAndFallsBackForUnsupportedCharges(){
        assertEquals(2.0 to 6.0,MinerPricing.estimate(2000000,1.0,3.0))
        assertNull(MinerPricing.estimate(2000000,null,3.0))
        assertNull(MinerPricing.estimate(2000000,-1.0,3.0))
        assertNull(MinerPricing.estimate(2000000,Double.NaN,3.0))
        val json="""{"data":[{"id":"vendor/model","pricing":{"prompt":"0.000001","completion":"0.000003","request":"0","image":"0"}}]}"""
        assertEquals(MinerPricing.Quote(1.0,3.0),MinerPricing.parse(json,"vendor/model"))
        assertNull(MinerPricing.parse(json,"missing"))
        assertNull(MinerPricing.parse(json.replace("\"request\":\"0\"","\"request\":\"1\""),"vendor/model"))
    }
    @Test fun persistsReservationAcrossRestartAndResetsOnlyOnNewDay(){
        val prefs=MemoryPrefs()
        val file=File("build/t45-miner-test.encrypted")
        var at=1_800_000_000_000L
        val cipher=object:KeyCipher{override fun encrypt(plain:ByteArray)=plain;override fun decrypt(data:ByteArray)=data}
        var store=MinerStore(file,cipher,prefs){at}
        assertTrue(store.autoStart);store.autoStart=false
        assertFalse(MinerStore(file,cipher,prefs){at}.autoStart)
        assertTrue(store.reserve(150000,200000));assertFalse(store.reserve(50001,200000))
        store=MinerStore(file,cipher,prefs){at};assertEquals(150000L,store.used())
        store.refund(10000,MinerPolicy.day(at));assertEquals(140000L,store.used())
        assertTrue(store.reserve(60000,200000));assertFalse(store.reserve(1,200000))
        val oldDay=MinerPolicy.day(at);at+=86_400_000
        assertEquals(0L,store.used());assertTrue(store.reserve(500,200000))
        store.refund(100,oldDay);assertEquals(500L,store.used())
        store.reconcile(500,250000,MinerPolicy.day(at));assertEquals(250000L,store.used())
        assertFalse(store.reserve(1,200000))
    }
    @Test fun keepsCloudPrivacyAndCostsVisibleInBothLanguages(){
        val en=com.wangzan101.mika.i18n.ResFileTexts.english
        val zh=com.wangzan101.mika.i18n.ResFileTexts.chinese
        assertTrue(en.strings.getValue("miner_cost").contains("own API account"))
        assertTrue(zh.strings.getValue("miner_cost").contains("自己 API 账户里的钱"))
        assertTrue(zh.strings.getValue("points_disclaimer").contains("违规刷来的金币会被扣回"))
        assertTrue(en.strings.getValue("points_disclaimer").contains("on-chain tokens"))
        assertTrue(zh.strings.getValue("miner_estimate").contains("上限跑满约花"))
        assertEquals("用的是 %1\$s 的 Key", zh.strings.getValue("miner_cloud"))
        assertEquals("Using your %1\$s key", en.strings.getValue("miner_cloud"))
        assertTrue(en.strings.getValue("miner_api_only").contains("Monthly subscriptions"))
        assertTrue(zh.strings.getValue("miner_api_only").contains("包月会员"))
    }
}
