// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StandbyRulesTest {
    @Test fun riskyMessagesEnterAndRoutineMessagesStayLocal() {
        listOf("公安要求转账至安全账户", "法院冻结账户", "请把验证码告诉客服", "妈我是女儿，急用钱转账", "妈我手机坏了，这是新号码", "恭喜中奖", "快递理赔退款", "访问 https://example.test", "访问 example.com/a", "请联系 13800138000").forEach {
            assertTrue(it, StandbyRules.candidate(it))
        }
        listOf("验证码 123456，五分钟内有效", "Your verification code is 123456", "快递到了，请联系13800138000", "银行余额变动100元", "明天见", "").forEach {
            assertFalse(it, StandbyRules.candidate(it))
        }
    }
    @Test fun redactionRunsBeforeAnySend() {
        val safe = StandbyRules.safe("验证码123456 卡号4111111111111111 密码:abcSecret")
        assertFalse(safe.contains("123456")); assertFalse(safe.contains("4111111111111111")); assertFalse(safe.contains("abcSecret"))
        assertEquals("8000", StandbyRules.suffix("+86 13800138000"))
        assertEquals("", StandbyRules.suffix("Bank"))
    }
    @Test fun reservationsDeduplicateAndApplyDailyLimitBeforeCalls() {
        val day = LocalDate.of(2026,11,6)
        val first = StandbyBudget().reserve("same content", day, 2)!!
        assertNull(first.reserve("same   content", day, 2))
        val second = first.reserve("another", day, 2)!!
        assertNull(second.reserve("third", day, 2))
        assertNotNull(second.reserve("third", day.plusDays(1), 2))
        assertFalse(first.hashes.toString().contains("same"))
    }
    @Test fun hybridAlwaysUsesIndependentOwnAndCommunityNeedsConsent() {
        assertEquals(StandbyRules.Route.Own, StandbyRules.route(false,false,false))
        assertEquals(StandbyRules.Route.Own, StandbyRules.route(true,true,false))
        assertEquals(StandbyRules.Route.Own, StandbyRules.route(true,true,true))
        assertEquals(StandbyRules.Route.Local, StandbyRules.route(false,true,false))
        assertEquals(StandbyRules.Route.Community, StandbyRules.route(false,true,true))
    }
    @Test fun suspiciousSmsFamilySharingIsPrivateByDefault() {
        val policy = com.wangzan101.mika.core.action.FamilyPolicy()
        assertEquals(com.wangzan101.mika.core.action.FamilyMode.Private, policy.mode("scamsms"))
        for (mode in com.wangzan101.mika.core.action.FamilyMode.entries) {
            assertFalse(policy.changed(com.wangzan101.mika.core.action.FamilyCategory.ScamSms, mode).active)
        }
    }
}
