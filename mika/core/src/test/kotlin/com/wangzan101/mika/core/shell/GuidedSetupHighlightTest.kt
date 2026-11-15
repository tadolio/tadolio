// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.shell

import org.junit.Assert.*
import org.junit.Test
import com.wangzan101.mika.core.shell.GuidedSetup.Bounds
import com.wangzan101.mika.core.shell.GuidedSetup.HintText
import com.wangzan101.mika.core.shell.GuidedSetup.Highlight
import com.wangzan101.mika.core.shell.GuidedSetup.Step
import com.wangzan101.mika.core.shell.GuidedSetup.Node

class GuidedSetupHighlightTest {
    private val box = Bounds(10, 100, 200, 160)
    private fun hints(step: Step, nodes: List<Node>, settings: Boolean = true, failed: Boolean = false) =
        GuidedSetup.highlights(step, nodes, nodes.map { box }, settings, clickFailed = failed)
    @Test fun recognizedManualAndDuplicateTargetsHaveTheirActualBounds() {
        val nodes = listOf(Node("Wireless debugging"), Node("Wireless debugging"))
        assertEquals(listOf(Highlight(box, HintText.Tap)), hints(Step.Wireless, nodes))
        assertEquals(box, hints(Step.Wireless, listOf(Node("Wireless debugging", true)), failed = true).single().bounds)
        assertTrue(hints(Step.Wireless, listOf(Node("Wireless debugging", true))).isEmpty())
    }
    @Test fun buildNumberRequestsSevenTaps() {
        assertEquals(HintText.BuildSeven, hints(Step.Developer, listOf(Node("Build number"))).single().text)
    }
    @Test fun passwordsAndForeignWindowsNeverDraw() {
        assertTrue(hints(Step.Developer, listOf(Node("Build number"), Node("", password = true))).isEmpty())
        assertTrue(hints(Step.Developer, listOf(Node("Build number"), Node("Enter PIN"))).isEmpty())
        assertTrue(hints(Step.Developer, listOf(Node("Build number")), false).isEmpty())
    }
    @Test fun missingOrInvalidTargetsNeverDraw() {
        assertTrue(hints(Step.Developer, listOf(Node("Unknown"))).isEmpty())
        assertTrue(GuidedSetup.highlights(Step.Developer, listOf(Node("Build number")), listOf(Bounds(0,0,0,0)), true).isEmpty())
        assertTrue(hints(Step.Done, listOf(Node("Build number"))).isEmpty())
    }
    @Test fun humanConsentTargetsRemainManualAndCheckedSwitchesDisappear() {
        assertEquals(1, hints(Step.Battery, listOf(Node("Tadolio"), Node("Allow"))).size)
        assertEquals(1, hints(Step.Notifications, listOf(Node("", checked = false))).size)
        assertTrue(hints(Step.Notifications, listOf(Node("", checked = true))).isEmpty())
        assertTrue(hints(Step.Battery, listOf(Node("Other app"))).isEmpty())
    }
}
