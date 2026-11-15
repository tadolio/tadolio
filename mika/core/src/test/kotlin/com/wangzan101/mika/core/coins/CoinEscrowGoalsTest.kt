// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.coins

import org.junit.Assert.assertEquals
import org.junit.Test

class CoinEscrowGoalsTest {
    private val id = "502a5e48-e244-4a84-8175-5ba33be320eb"

    @Test fun bubbleHidesToolHint() {
        assertEquals("同意付款约定", CoinEscrowGoals.visible("同意付款约定\nhivo_coins_accept receipt=$id"))
        assertEquals("确认", CoinEscrowGoals.visible(CoinEscrowGoals.release(id, "确认", 1)))
        assertEquals("申诉", CoinEscrowGoals.visible(CoinEscrowGoals.dispute(id, "申诉")))
    }

    @Test fun ordinaryTextUntouched() {
        val typed = "帮我看看\nhivo_coins_accept receipt=abc"
        assertEquals(typed, CoinEscrowGoals.visible(typed))
        assertEquals("hivo_coins_accept receipt=$id", CoinEscrowGoals.visible("hivo_coins_accept receipt=$id"))
    }
}
