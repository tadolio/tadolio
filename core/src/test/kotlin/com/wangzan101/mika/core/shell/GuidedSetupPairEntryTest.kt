// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedSetupPairEntryTest {
    private val wireless = GuidedSetup.Node("无线调试", clickable = true)
    private val pair = GuidedSetup.Node("使用配对码配对设备", clickable = true)
    private val other = GuidedSetup.Node("USB 调试", clickable = true)

    @Test fun developerListEntersTheWirelessPageFirst() {
        assertEquals(GuidedSetup.Action.Click(1), GuidedSetup.action(GuidedSetup.Step.Pair, listOf(other, wireless), true))
    }

    @Test fun pairingRowWinsOnTheWirelessPage() {
        assertEquals(GuidedSetup.Action.Click(1), GuidedSetup.action(GuidedSetup.Step.Pair, listOf(wireless, pair), true))
    }

    @Test fun aCheckedWirelessSwitchIsNeverClicked() {
        val on = wireless.copy(checked = true)
        assertEquals(GuidedSetup.Action.Manual, GuidedSetup.action(GuidedSetup.Step.Pair, listOf(other, on), true))
    }
}
